package hnovel.execution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import hnovel.rhino.HostBridge
import hnovel.rhino.RhinoScriptEngine
import hnovel.rhino.ScriptFrame
import hnovel.rhino.ScriptLimits
import hnovel.rhino.ScriptResult
import hnovel.rhino.ScriptLibrary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable data class ExecutionIdentity(val sourceId: String, val profile: String, val revision: String, val nonce: String,
 val namespace: String = "default", val accountGeneration: Long = 0) {
 init { require(sourceId.isNotBlank() && profile.isNotBlank() && revision.isNotBlank() && nonce.isNotBlank() && namespace.isNotBlank() && accountGeneration >= 0) }
}
@Serializable data class ExecutionLimits(val timeoutMillis: Long = 5000, val maxOutputBytes: Int = 65536, val maxRequests: Int = 16) {
 init { require(timeoutMillis in 1..60000 && maxOutputBytes in 1..4 * 1024 * 1024 && maxRequests in 0..1024) }
}
@Serializable sealed interface ExecutionTask {
 @Serializable data class Echo(val value: String): ExecutionTask
 @Serializable data class Sleep(val millis: Long): ExecutionTask
 @Serializable data class Script(val code: String, val result: JsonElement = JsonNull, val bookId: String? = null,
  val chapterId: String? = null, val key: String = "", val page: Int = 1, val baseUrl: String = "",
  val libraryCode: String? = null) : ExecutionTask
}
@Serializable sealed interface ExecutionResult {
 @Serializable data class Success(val output: String): ExecutionResult
 @Serializable data class Failure(val code: FailureCode): ExecutionResult
}
@Serializable enum class FailureCode { Timeout, ProcessExited, InvalidIdentity, OutputLimit, InvalidTask, Cancelled, Revoked, Busy, InputLimit, ScriptSyntax, ScriptRuntime, BridgeDenied }

/** Host authority for source identities. The worker never gets a method to issue or change a ticket. */
class ExecutionAuthority {
 private val active = ConcurrentHashMap<String, ExecutionIdentity>()
 @Synchronized fun issue(sourceId: String, profile: String, revision: String, namespace: String = "default", accountGeneration: Long = 0): ExecutionIdentity =
  ExecutionIdentity(sourceId, profile, revision, UUID.randomUUID().toString(), namespace, accountGeneration).also { active[it.nonce] = it }
 @Synchronized fun revoke(identity: ExecutionIdentity) { active.remove(identity.nonce, identity) }
 @Synchronized fun revokeSource(sourceId: String) { active.entries.removeIf { it.value.sourceId == sourceId } }
 fun accepts(identity: ExecutionIdentity) = active[identity.nonce] == identity
 /** Storage/result commits and revocation share this lock; no new commit starts after revocation. */
 @Synchronized fun <T> authorized(identity: ExecutionIdentity, block: () -> T): T {
  check(accepts(identity)) { "Execution revoked" }
  return block()
 }
}

/** Host-side boundary. Each invocation receives a fresh process and a host-issued identity. */
class IsolatedExecutor(private val javaCommand: String = javaHome(), private val classPath: String = workerClassPath(),
 private val authority: ExecutionAuthority? = null) {
 fun execute(identity: ExecutionIdentity, task: ExecutionTask, limits: ExecutionLimits = ExecutionLimits()): ExecutionResult {
  if (identity.sourceId.isBlank() || (authority != null && !authority.accepts(identity))) return ExecutionResult.Failure(FailureCode.InvalidIdentity)
  val process = try { ProcessBuilder(javaCommand, "-cp", classPath, WorkerMain::class.java.name).start() }
    catch (_: Exception) { return ExecutionResult.Failure(FailureCode.ProcessExited) }
  try {
   val request = Wire(identity, task, limits)
   process.outputStream.bufferedWriter().use { it.write(kotlinx.serialization.json.Json.encodeToString(Wire.serializer(), request)); it.newLine(); it.flush() }
   val deadline = System.nanoTime() + limits.timeoutMillis * 1_000_000
   while (process.isAlive && System.nanoTime() < deadline) {
    if (authority != null && !authority.accepts(identity)) { process.destroyForcibly(); process.waitFor(); return ExecutionResult.Failure(FailureCode.Revoked) }
    Thread.sleep(5)
   }
   if (process.isAlive) { process.destroyForcibly(); process.waitFor(); return ExecutionResult.Failure(FailureCode.Timeout) }
   val line = process.inputStream.bufferedReader().readLine() ?: return ExecutionResult.Failure(FailureCode.ProcessExited)
   if (authority != null && !authority.accepts(identity)) return ExecutionResult.Failure(FailureCode.Revoked)
   return kotlinx.serialization.json.Json.decodeFromString(ExecutionResult.serializer(), line)
  } catch (_: InterruptedException) { process.destroyForcibly(); Thread.currentThread().interrupt(); return ExecutionResult.Failure(FailureCode.Cancelled) }
    catch (_: Exception) { process.destroyForcibly(); return ExecutionResult.Failure(FailureCode.ProcessExited) }
  finally { if (process.isAlive) process.destroyForcibly() }
 }
 companion object {
  private fun javaHome() = java.nio.file.Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java").toString()

  // Gradle/plugin hosts can load these classes outside java.class.path. Locate the actual worker
  // and its runtime dependencies; callers with non-file classloaders must pass a packaged classpath.
  private fun workerClassPath(): String = listOf(WorkerMain::class.java, Unit::class.java,
   kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.Json::class.java,
   RhinoScriptEngine::class.java, org.mozilla.javascript.Context::class.java)
   .map { type ->
    val location = requireNotNull(type.protectionDomain?.codeSource?.location) { "Supply a worker runtime classpath" }
    require(location.protocol == "file") { "Supply a packaged worker runtime classpath" }
    java.nio.file.Paths.get(location.toURI()).toString()
   }.distinct().joinToString(java.io.File.pathSeparator)
 }
}
@Serializable private data class Wire(val identity: ExecutionIdentity, val task: ExecutionTask, val limits: ExecutionLimits)

/** Shared Android/JVM wire encoding; the authority stays in the host. */
object ExecutionWire {
 fun encode(identity: ExecutionIdentity, task: ExecutionTask, limits: ExecutionLimits): ByteArray =
  kotlinx.serialization.json.Json.encodeToString(Wire.serializer(), Wire(identity, task, limits)).toByteArray(Charsets.UTF_8)
 fun encodeResult(result: ExecutionResult): ByteArray =
  kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), result).toByteArray(Charsets.UTF_8)
 fun decodeResult(bytes: ByteArray): ExecutionResult =
  kotlinx.serialization.json.Json.decodeFromString(ExecutionResult.serializer(), bytes.toString(Charsets.UTF_8))
}

/** Untrusted-side worker. It receives only the bound DTO and has no host repository/client references. */
class WorkerRuntime : AutoCloseable {
 private data class LibraryOwner(val namespace: String, val source: String, val profile: String,
  val revision: String, val accountGeneration: Long)
 private data class LibraryEntry(val code: String, val library: ScriptLibrary)
 private val libraries = LinkedHashMap<LibraryOwner, LibraryEntry>(16, 0.75f, true)

 private fun library(identity: ExecutionIdentity, code: String?): ScriptLibrary? {
  if (code.isNullOrBlank()) return null
  val key = LibraryOwner(identity.namespace, identity.sourceId, identity.profile, identity.revision, identity.accountGeneration)
  val current = libraries[key]
  if (current?.code == code) return current.library
  current?.library?.close()
  val entry = LibraryEntry(code, ScriptLibrary(identity.sourceId, identity.profile, code))
  libraries[key] = entry
  if (libraries.size > 16) {
   val oldest = libraries.entries.iterator()
   oldest.next().value.library.close()
   oldest.remove()
  }
  return entry.library
 }

 @Synchronized override fun close() {
  libraries.values.forEach { it.library.close() }
  libraries.clear()
 }

 @Synchronized fun executeSerialized(input: String, bridge: HostBridge = HostBridge { _, _ -> error("No host broker") }): String {
  val wire = try { kotlinx.serialization.json.Json.decodeFromString(Wire.serializer(), input) }
    catch (_: Exception) { return kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), ExecutionResult.Failure(FailureCode.InvalidTask)) }
  val result = when (val task = wire.task) {
   is ExecutionTask.Echo -> if (task.value.toByteArray().size > wire.limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit) else ExecutionResult.Success(task.value)
   is ExecutionTask.Sleep -> { Thread.sleep(task.millis); ExecutionResult.Success("slept") }
   is ExecutionTask.Script -> {
    val frame = ScriptFrame(wire.identity.sourceId, wire.identity.profile, task.bookId, task.chapterId,
     mapOf("result" to task.result), task.key, task.page, task.baseUrl)
    when (val evaluated = RhinoScriptEngine(bridge, ScriptLimits(maxResultChars = wire.limits.maxOutputBytes))
     .evaluate(task.code, frame, library(wire.identity, task.libraryCode))) {
     is ScriptResult.Success -> if (evaluated.json.toByteArray(Charsets.UTF_8).size > wire.limits.maxOutputBytes)
      ExecutionResult.Failure(FailureCode.OutputLimit) else ExecutionResult.Success(evaluated.json)
     is ScriptResult.Failure -> ExecutionResult.Failure(when (evaluated.code) {
      hnovel.rhino.FailureCode.Timeout -> FailureCode.Timeout
      hnovel.rhino.FailureCode.Cancelled -> FailureCode.Cancelled
      hnovel.rhino.FailureCode.Syntax -> FailureCode.ScriptSyntax
      hnovel.rhino.FailureCode.BridgeDenied -> FailureCode.BridgeDenied
      hnovel.rhino.FailureCode.ResultTooLarge -> FailureCode.OutputLimit
      else -> FailureCode.ScriptRuntime
     })
    }
   }
  }
  return kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), result)
 }
}

object WorkerMain {
 fun executeSerialized(input: String, bridge: HostBridge = HostBridge { _, _ -> error("No host broker") }): String =
  WorkerRuntime().use { it.executeSerialized(input, bridge) }

 @JvmStatic fun main(args: Array<String>) {
  val input = generateSequence { readLine() }.firstOrNull() ?: return
  print(executeSerialized(input))
 }
}
