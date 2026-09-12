package hnovel.execution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import hnovel.rhino.HostBridge
import hnovel.rhino.RhinoScriptEngine
import hnovel.rhino.ScriptFrame
import hnovel.rhino.ScriptLimits
import hnovel.rhino.ScriptResult
import hnovel.rhino.ScriptLibrary
import hnovel.rules.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private class WorkerOutputLimit : RuntimeException()

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
 @Serializable data class ContentMarkup(val html: String,
  val location: RuleLocation = RuleLocation("ruleContent.parts")) : ExecutionTask
 @Serializable data class Script(val code: String, val result: JsonElement = JsonNull, val bookId: String? = null,
  val chapterId: String? = null, val key: String = "", val page: Int = 1, val baseUrl: String = "",
  val libraryCode: String? = null, val book: JsonObject = JsonObject(emptyMap()),
  val chapter: JsonObject = JsonObject(emptyMap()), val chineseConverter: Int = 0) : ExecutionTask
 @Serializable data class Rule(val rule: String, val input: RuleValue, val output: OutputKind = OutputKind.TextList,
  val location: RuleLocation = RuleLocation("rule"), val bookId: String? = null, val chapterId: String? = null,
  val key: String = "", val page: Int = 1, val baseUrl: String = "", val libraryCode: String? = null,
  val sourceVariables: Map<String, String> = emptyMap(), val bookVariables: Map<String, String> = emptyMap(),
  val chapterVariables: Map<String, String> = emptyMap(), val book: JsonObject = JsonObject(emptyMap()),
  val chapter: JsonObject = JsonObject(emptyMap()), val bookBigVariables: Map<String, String> = emptyMap(),
  val chapterBigVariables: Map<String, String> = emptyMap(), val chineseConverter: Int = 0,
  val unescapeHtml: Boolean = true, val sourceHeaderRule: String = "", val discovery: JsonObject? = null) : ExecutionTask
}

fun ExecutionTask.libraryCode(): String? = when (this) {
 is ExecutionTask.Script -> libraryCode
 is ExecutionTask.Rule -> libraryCode
 else -> null
}
@Serializable sealed interface ExecutionResult {
 @Serializable data class Success(val output: String): ExecutionResult
 @Serializable data class Failure(val code: FailureCode, val ruleError: RuleError? = null): ExecutionResult
}
@Serializable enum class FailureCode { Timeout, ProcessExited, InvalidIdentity, OutputLimit, InvalidTask, Cancelled, Revoked, Busy, InputLimit, ScriptSyntax, ScriptRuntime, BridgeDenied, RuleRuntime }

/** Host authority for source identities. The worker never gets a method to issue or change a ticket. */
class ExecutionAuthority {
 private val active = ConcurrentHashMap<String, ExecutionIdentity>()
 private val sessions = ConcurrentHashMap<String, hnovel.network.SourceSession>()
 @Synchronized fun issue(sourceId: String, profile: String, revision: String, namespace: String = "default", accountGeneration: Long = 0): ExecutionIdentity =
  ExecutionIdentity(sourceId, profile, revision, UUID.randomUUID().toString(), namespace, accountGeneration).also { active[it.nonce] = it }
 @Synchronized fun revoke(identity: ExecutionIdentity) {
  if (active.remove(identity.nonce, identity)) sessions.remove(identity.nonce)
 }
 @Synchronized fun revokeSource(sourceId: String, namespace: String) {
  active.values.filter { it.sourceId == sourceId && it.namespace == namespace }.forEach(::revoke)
 }
 /** Atomically retain the host-prepared replacement ticket while retiring the old registration. */
 @Synchronized fun replaceSource(replacement: ExecutionIdentity, commit: () -> Unit) {
  check(accepts(replacement))
  commit()
  active.values.filter { it.sourceId == replacement.sourceId && it.namespace == replacement.namespace && it != replacement }.forEach(::revoke)
 }
 @Synchronized internal fun bindSession(identity: ExecutionIdentity, session: hnovel.network.SourceSession) {
  check(accepts(identity) && !session.closed)
  val previous = sessions.putIfAbsent(identity.nonce, session)
  check(previous == null || previous === session) { "Execution ticket already belongs to another session" }
 }
 @Synchronized fun accepts(identity: ExecutionIdentity): Boolean {
  if (active[identity.nonce] != identity) return false
  if (sessions[identity.nonce]?.closed == true) { revoke(identity); return false }
  return true
 }
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
  val input = ExecutionWire.encode(identity, task, limits)
  if (input.size > BridgeWire.MAX_BYTES) return ExecutionResult.Failure(FailureCode.InputLimit)
  val deadline = System.nanoTime() + limits.timeoutMillis * 1_000_000
  val process = try { ProcessBuilder(javaCommand, "-Xmx64m", "-Xss1m", "-XX:+ExitOnOutOfMemoryError",
   "-cp", classPath, WorkerMain::class.java.name).start() }
    catch (_: Exception) { return ExecutionResult.Failure(FailureCode.ProcessExited) }
  val pipes = Executors.newFixedThreadPool(2) { Thread(it, "source-worker-io").apply { isDaemon = true } }
  try {
   val writing = pipes.submit { process.outputStream.use { it.write(input); it.write('\n'.code); it.flush() } }
   // Drain while the child runs: waiting for exit first deadlocks when a valid result fills
   // the OS pipe. Bound bytes before JSON decoding, as on Android's Binder boundary.
   val reading = pipes.submit<ByteArray> {
    process.inputStream.use { stream ->
     val output = java.io.ByteArrayOutputStream()
     val buffer = ByteArray(8192)
     while (true) {
      val count = stream.read(buffer)
      if (count < 0) break
      if (output.size() + count > BridgeWire.MAX_BYTES) throw WorkerOutputLimit()
      output.write(buffer, 0, count)
     }
     output.toByteArray()
    }
   }
   while (process.isAlive && System.nanoTime() < deadline) {
    if (authority != null && !authority.accepts(identity)) { process.destroyForcibly(); process.waitFor(); return ExecutionResult.Failure(FailureCode.Revoked) }
    if (writing.isDone) writing.get()
    if (reading.isDone) reading.get()
    Thread.sleep(5)
   }
   // Startup/pipe scheduling also consumes the deadline; revocation during startup must
   // retain its identity failure even if the child was not ready before that deadline.
   if (authority != null && !authority.accepts(identity)) return ExecutionResult.Failure(FailureCode.Revoked)
   if (process.isAlive) { process.destroyForcibly(); process.waitFor(); return ExecutionResult.Failure(FailureCode.Timeout) }
   val bytes = reading.get(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
   if (authority != null && !authority.accepts(identity)) return ExecutionResult.Failure(FailureCode.Revoked)
   return ExecutionWire.decodeResult(bytes)
  } catch (_: InterruptedException) { process.destroyForcibly(); Thread.currentThread().interrupt(); return ExecutionResult.Failure(FailureCode.Cancelled) }
    catch (failure: ExecutionException) { return ExecutionResult.Failure(if (failure.cause is WorkerOutputLimit) FailureCode.OutputLimit else FailureCode.ProcessExited) }
    catch (_: TimeoutException) { return ExecutionResult.Failure(FailureCode.Timeout) }
    catch (_: Exception) { process.destroyForcibly(); return ExecutionResult.Failure(FailureCode.ProcessExited) }
  finally { if (process.isAlive) process.destroyForcibly(); pipes.shutdownNow() }
 }
 companion object {
  private fun javaHome() = java.nio.file.Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java").toString()

  // Gradle/plugin hosts can load these classes outside java.class.path. Locate the actual worker
  // and its runtime dependencies; callers with non-file classloaders must pass a packaged classpath.
  private fun workerClassPath(): String = (listOf(WorkerMain::class.java, Unit::class.java,
   kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.Json::class.java,
   RhinoScriptEngine::class.java, org.mozilla.javascript.Context::class.java, RuleEvaluator::class.java) +
   listOf("org.jsoup.Jsoup", "com.jayway.jsonpath.JsonPath", "net.minidev.json.JSONValue", "net.minidev.asm.BeansAccess",
    "org.objectweb.asm.ClassReader", "org.slf4j.LoggerFactory", "org.seimicrawler.xpath.JXDocument",
    "org.apache.commons.lang3.StringUtils", "org.antlr.v4.runtime.Parser", "com.google.gson.Gson",
    "cn.hutool.crypto.KeyUtil", "cn.hutool.core.util.HexUtil", "com.github.liuyueyi.quick.transfer.ChineseUtils",
    "hnovel.rhino.charset.CharsetDetector", "hnovel.rhino.font.QueryTTF", "okhttp3.Response", "okio.Buffer").map { Class.forName(it) })
   .map { type ->
    val location = requireNotNull(type.protectionDomain?.codeSource?.location) { "Supply a worker runtime classpath" }
    require(location.protocol == "file") { "Supply a packaged worker runtime classpath" }
    java.nio.file.Paths.get(location.toURI()).toString()
   }.distinct().joinToString(java.io.File.pathSeparator)
 }
}
@Serializable private data class Wire(val identity: ExecutionIdentity, val task: ExecutionTask, val limits: ExecutionLimits,
 val libraryScripts: List<String>? = null)

/** Shared Android/JVM wire encoding; the authority stays in the host. */
object ExecutionWire {
 fun encode(identity: ExecutionIdentity, task: ExecutionTask, limits: ExecutionLimits, libraryScripts: List<String>? = null): ByteArray =
  kotlinx.serialization.json.Json.encodeToString(Wire.serializer(), Wire(identity, task, limits, libraryScripts)).toByteArray(Charsets.UTF_8)
 fun encodeResult(result: ExecutionResult): ByteArray =
  kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), result).toByteArray(Charsets.UTF_8)
 fun decodeResult(bytes: ByteArray): ExecutionResult =
  kotlinx.serialization.json.Json.decodeFromString(ExecutionResult.serializer(), BridgeWire.validate(bytes))
}

/** Untrusted-side worker. It receives only the bound DTO and has no host repository/client references. */
class WorkerRuntime(private val archives: hnovel.rhino.ArchiveDecoder = hnovel.rhino.ArchiveDecoder.Zip) : AutoCloseable {
 private data class LibraryOwner(val namespace: String, val source: String, val profile: String,
  val revision: String, val accountGeneration: Long)
 private data class LibraryEntry(val code: String, val scripts: List<String>, val library: ScriptLibrary)
 private val libraries = LinkedHashMap<LibraryOwner, LibraryEntry>(16, 0.75f, true)

 private fun library(identity: ExecutionIdentity, code: String?, resolved: List<String>?): ScriptLibrary? {
  if (code.isNullOrBlank()) return null
  val key = LibraryOwner(identity.namespace, identity.sourceId, identity.profile, identity.revision, identity.accountGeneration)
  val current = libraries[key]
  val scripts = resolved ?: listOf(code)
  if (current?.code == code && current.scripts == scripts) return current.library
  current?.library?.close()
  val entry = LibraryEntry(code, scripts, ScriptLibrary(identity.sourceId, identity.profile, scripts))
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
  if (SourceLibraryDefinition.isUrlMap(wire.task.libraryCode()) && wire.libraryScripts == null)
   return kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), ExecutionResult.Failure(FailureCode.BridgeDenied))
  val result = when (val task = wire.task) {
   is ExecutionTask.ContentMarkup -> WorkerContentMarkup.evaluate(task, wire.limits)
   is ExecutionTask.Rule -> WorkerRuleEvaluator.evaluate(task, wire.identity, wire.limits, bridge,
    library(wire.identity, task.libraryCode, wire.libraryScripts), archives)
   is ExecutionTask.Echo -> if (task.value.toByteArray().size > wire.limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit) else ExecutionResult.Success(task.value)
   is ExecutionTask.Sleep -> { Thread.sleep(task.millis); ExecutionResult.Success("slept") }
   is ExecutionTask.Script -> {
    val frame = ScriptFrame(wire.identity.sourceId, wire.identity.profile, task.bookId, task.chapterId,
     mapOf("result" to task.result), task.key, task.page, task.baseUrl, book = task.book, chapter = task.chapter, chineseConverter = task.chineseConverter)
    when (val evaluated = RhinoScriptEngine(bridge, ScriptLimits(maxResultChars = wire.limits.maxOutputBytes), archives)
     .evaluate(task.code, frame, library(wire.identity, task.libraryCode, wire.libraryScripts))) {
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
