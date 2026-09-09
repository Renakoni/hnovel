package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionWire
import hnovel.execution.FailureCode
import hnovel.execution.WorkerMain
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Real Binder transport in an isolated UID. Only the installed app's UID can submit work. */
class IsolatedExecutionService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean()

    private fun enforceHost() {
        // onBind runs on the main thread, outside the client's Binder transaction.
        // The package UID is trusted platform metadata, unlike a value supplied in an Intent.
        if (Binder.getCallingUid() != applicationInfo.uid) throw SecurityException("unauthorized_caller")
    }

    private val binder = object : IIsolatedExecutionService.Stub() {
        override fun workerUid(): Int {
            enforceHost()
            return Process.myUid()
        }

        override fun execute(request: ByteArray, callback: IExecutionCallback) {
            enforceHost()
            if (request.size > MAX_IPC_BYTES) {
                deliver(callback, ExecutionWire.encodeResult(ExecutionResult.Failure(FailureCode.InputLimit)))
                return
            }
            if (!running.compareAndSet(false, true)) {
                deliver(callback, ExecutionWire.encodeResult(ExecutionResult.Failure(FailureCode.Busy)))
                return
            }
            executor.execute {
                try {
                    val result = try {
                        WorkerMain.executeSerialized(request.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    } catch (_: Exception) {
                        ExecutionWire.encodeResult(ExecutionResult.Failure(FailureCode.InvalidTask))
                    }
                    deliver(callback, if (result.size <= MAX_IPC_BYTES) result else
                        ExecutionWire.encodeResult(ExecutionResult.Failure(FailureCode.OutputLimit)))
                } finally {
                    running.set(false)
                }
            }
        }

        override fun terminate() {
            enforceHost()
            Process.killProcess(Process.myPid())
        }
    }

    private fun deliver(callback: IExecutionCallback, result: ByteArray) {
        try { callback.onResult(result) } catch (_: RemoteException) {
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    companion object { const val MAX_IPC_BYTES = 256 * 1024 }
}
