package hnovel.execution
import org.junit.Assert.*
import org.junit.Test
class IsolatedExecutorTest {
 @Test fun workerDoesNotDependOnTheHostsBootstrapClasspath() {
  val original = System.getProperty("java.class.path")
  try {
   System.setProperty("java.class.path", "host-bootstrap-without-worker.jar")
   assertEquals(ExecutionResult.Success("loaded from actual code sources"),
    IsolatedExecutor().execute(id, ExecutionTask.Echo("loaded from actual code sources")))
  } finally { if (original == null) System.clearProperty("java.class.path") else System.setProperty("java.class.path", original) }
 }
 private val id=ExecutionIdentity("source-a","legado","r1","nonce")
 @Test fun completesAndReturnsBoundedResult() { assertEquals(ExecutionResult.Success("ok"), IsolatedExecutor().execute(id,ExecutionTask.Echo("ok"))) }
 @Test fun hungWorkerIsKilledByHostDeadline() { assertEquals(ExecutionResult.Failure(FailureCode.Timeout), IsolatedExecutor().execute(id,ExecutionTask.Sleep(10000),ExecutionLimits(100,1000,1))) }
 @Test fun outputBudgetIsEnforcedInsideWorker() { assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), IsolatedExecutor().execute(id,ExecutionTask.Echo("12345"),ExecutionLimits(1000,4,1))) }
}
