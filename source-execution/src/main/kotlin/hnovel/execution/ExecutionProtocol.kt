package hnovel.execution

import kotlinx.serialization.Serializable

@Serializable data class ExecutionIdentity(val sourceId: String, val profile: String, val revision: String, val nonce: String) {
 init { require(sourceId.isNotBlank() && profile.isNotBlank() && revision.isNotBlank() && nonce.isNotBlank()) }
}
@Serializable data class ExecutionLimits(val timeoutMillis: Long = 5000, val maxOutputBytes: Int = 65536, val maxRequests: Int = 16) {
 init { require(timeoutMillis in 1..60000 && maxOutputBytes in 1..4 * 1024 * 1024 && maxRequests in 0..1024) }
}
@Serializable sealed interface ExecutionTask {
 @Serializable data class Echo(val value: String): ExecutionTask
 @Serializable data class Sleep(val millis: Long): ExecutionTask
}
@Serializable sealed interface ExecutionResult {
 @Serializable data class Success(val output: String): ExecutionResult
 @Serializable data class Failure(val code: FailureCode): ExecutionResult
}
@Serializable enum class FailureCode { Timeout, ProcessExited, InvalidIdentity, OutputLimit, InvalidTask, Cancelled }

/** Host-side boundary. Each invocation receives a fresh process and a host-issued identity. */
class IsolatedExecutor(private val javaCommand: String = javaHome(), private val classPath: String = System.getProperty("java.class.path")) {
 fun execute(identity: ExecutionIdentity, task: ExecutionTask, limits: ExecutionLimits = ExecutionLimits()): ExecutionResult {
  if (identity.sourceId.isBlank()) return ExecutionResult.Failure(FailureCode.InvalidIdentity)
  val process = try { ProcessBuilder(javaCommand, "-cp", classPath, WorkerMain::class.java.name).start() }
    catch (_: Exception) { return ExecutionResult.Failure(FailureCode.ProcessExited) }
  try {
   val request = Wire(identity, task, limits)
   process.outputStream.bufferedWriter().use { it.write(kotlinx.serialization.json.Json.encodeToString(Wire.serializer(), request)); it.newLine(); it.flush() }
   val deadline = System.nanoTime() + limits.timeoutMillis * 1_000_000
   while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
   if (process.isAlive) { process.destroyForcibly(); process.waitFor(); return ExecutionResult.Failure(FailureCode.Timeout) }
   val line = process.inputStream.bufferedReader().readLine() ?: return ExecutionResult.Failure(FailureCode.ProcessExited)
   return kotlinx.serialization.json.Json.decodeFromString(ExecutionResult.serializer(), line)
  } catch (_: InterruptedException) { process.destroyForcibly(); Thread.currentThread().interrupt(); return ExecutionResult.Failure(FailureCode.Cancelled) }
    catch (_: Exception) { process.destroyForcibly(); return ExecutionResult.Failure(FailureCode.ProcessExited) }
  finally { if (process.isAlive) process.destroyForcibly() }
 }
 companion object { private fun javaHome() = java.nio.file.Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java").toString() }
}
@Serializable private data class Wire(val identity: ExecutionIdentity, val task: ExecutionTask, val limits: ExecutionLimits)

/** Untrusted-side worker. It receives only the bound DTO and has no host repository/client references. */
object WorkerMain {
 @JvmStatic fun main(args: Array<String>) {
  val input = generateSequence { readLine() }.firstOrNull() ?: return
  val wire = try { kotlinx.serialization.json.Json.decodeFromString(Wire.serializer(), input) } catch (_: Exception) { print(kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), ExecutionResult.Failure(FailureCode.InvalidTask))); return }
  val result = when (val task = wire.task) { is ExecutionTask.Echo -> if (task.value.toByteArray().size > wire.limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit) else ExecutionResult.Success(task.value)
   is ExecutionTask.Sleep -> { Thread.sleep(task.millis); ExecutionResult.Success("slept") } }
  print(kotlinx.serialization.json.Json.encodeToString(ExecutionResult.serializer(), result))
 }
}
