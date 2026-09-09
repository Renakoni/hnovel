package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.ExecutionWire
import hnovel.execution.FailureCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Binder/UID tests. A Robolectric service is not a substitute for this suite. */
@RunWith(AndroidJUnit4::class)
class IsolatedExecutionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun remoteBinderUsesIsolatedUidAndEnforcesWireLimits() = runBlocking {
        val connected = CompletableDeferred<IIsolatedExecutionService>()
        val death = CompletableDeferred<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                binder.linkToDeath({ death.complete(Unit) }, 0)
                connected.complete(IIsolatedExecutionService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, IsolatedExecutionService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            val service = withTimeout(15000) { connected.await() }
            assertNotEquals(Process.myUid(), service.workerUid())
            assertNull(service.asBinder().queryLocalInterface(IIsolatedExecutionService.Stub.DESCRIPTOR))
            assertForeignUidRejected(service.asBinder())
            val result = CompletableDeferred<ExecutionResult>()
            service.execute(ByteArray(IsolatedExecutionService.MAX_IPC_BYTES + 1), object : IExecutionCallback.Stub() {
                override fun onResult(bytes: ByteArray) { result.complete(ExecutionWire.decodeResult(bytes)) }
            })
            assertEquals(ExecutionResult.Failure(FailureCode.InputLimit), withTimeout(5000) { result.await() })
            service.terminate()
            withTimeout(5000) { death.await() }
        } finally { context.unbindService(connection) }
    }

    private suspend fun assertForeignUidRejected(service: IBinder) {
        val connected = CompletableDeferred<IForeignExecutionProbe>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(IForeignExecutionProbe.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, ForeignExecutionProbeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue(withTimeout(15000) { connected.await() }.isRejected(service)) }
        finally { context.unbindService(connection) }
    }

    @Test fun timeoutKillsWorkerAndAnotherSourceCanStart() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val a = authority.issue("source-a", "legado", "1")
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), executor.execute(a,
            ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 4000)))
        val b = authority.issue("source-b", "legado", "1")
        assertEquals(ExecutionResult.Success("new process"), executor.execute(b,
            ExecutionTask.Echo("new process"), ExecutionLimits(timeoutMillis = 15000)))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidIdentity), executor.execute(
            b.copy(sourceId = "source-a"), ExecutionTask.Echo("forged")))
    }

    @Test fun revokeAndCancellationRetireWorkers() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val a = authority.issue("source-a", "legado", "1")
        val pending = async { executor.execute(a, ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 15000)) }
        delay(1500)
        authority.revoke(a)
        assertEquals(ExecutionResult.Failure(FailureCode.Revoked), withTimeout(5000) { pending.await() })
        val b = authority.issue("source-b", "legado", "1")
        val cancelled = async { executor.execute(b, ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 15000)) }
        delay(1500)
        cancelled.cancel()
        withTimeout(5000) { cancelled.join() }
        assertEquals(ExecutionResult.Success("after cancellation"), executor.execute(b,
            ExecutionTask.Echo("after cancellation"), ExecutionLimits(timeoutMillis = 15000)))
    }
}
