package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.ExecutionWire
import hnovel.execution.FailureCode
import hnovel.execution.SourceExecutionBroker
import hnovel.execution.BridgeWire
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** One serialized worker on API 24+. Library scopes survive successful calls, never failed processes. */
class AndroidIsolatedExecutor(context: Context, private val authority: ExecutionAuthority) {
    private val context = context.applicationContext

    suspend fun execute(identity: ExecutionIdentity, task: ExecutionTask,
        limits: ExecutionLimits = ExecutionLimits(), broker: SourceExecutionBroker? = null): ExecutionResult = withContext(Dispatchers.IO) {
        if (broker != null && (broker.identity != identity || broker.limits != limits)) return@withContext failure(FailureCode.InvalidIdentity)
        if (!authority.accepts(identity)) return@withContext failure(FailureCode.InvalidIdentity)
        val request = ExecutionWire.encode(identity, task, limits)
        if (request.size > IsolatedExecutionService.MAX_IPC_BYTES) return@withContext failure(FailureCode.InputLimit)
        if (!workerLock.tryLock()) return@withContext failure(FailureCode.Busy)
        try {
            // A new bind must never reuse a worker whose previous shutdown has not completed.
            if (retiringBinder?.isBinderAlive == true) return@withContext failure(FailureCode.ProcessExited)
            val previous = retainedWorker
            if (previous != null && (previous.authority !== authority || previous.died.isCompleted)) retire(previous)
            if (retiringBinder?.isBinderAlive == true) return@withContext failure(FailureCode.ProcessExited)
            val worker = retainedWorker ?: IsolatedWorkerConnection(context, authority).also { retainedWorker = it }
            if (task is ExecutionTask.Script && !task.libraryCode.isNullOrBlank()) worker.retainsLibraries = true
            invoke(worker, identity, request, limits, broker)
        } finally {
            workerLock.unlock()
        }
    }

    /** Release retained library state when the owning runtime is shut down. */
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        workerLock.withLock { retainedWorker?.takeIf { it.authority === authority }?.let { retire(it) } }
    }

    private suspend fun retire(worker: IsolatedWorkerConnection) {
        retiringBinder = worker.remote?.asBinder()
        try { worker.close() } finally { if (retainedWorker === worker) retainedWorker = null }
    }

    private suspend fun invoke(worker: IsolatedWorkerConnection, identity: ExecutionIdentity, request: ByteArray,
        limits: ExecutionLimits, broker: SourceExecutionBroker?): ExecutionResult = coroutineScope {
        val result = CompletableDeferred<ExecutionResult>()
        val finished = AtomicBoolean()
        val callingBroker = AtomicBoolean()
        var keepWorker = false
        val brokerCalls = SupervisorJob(coroutineContext[Job])
        val revocations = launch {
            while (!result.isCompleted) {
                if (!authority.accepts(identity)) {
                    result.complete(failure(FailureCode.Revoked))
                    break
                }
                delay(10)
            }
        }
        try {
            val completed = withTimeoutOrNull(limits.timeoutMillis) {
                val service = select<IIsolatedExecutionService?> {
                    worker.connected.onAwait { it }
                    worker.died.onAwait { null }
                    result.onAwait { null }
                } ?: return@withTimeoutOrNull if (result.isCompleted) result.await() else failure(FailureCode.ProcessExited)
                val workerUid = service.workerUid()
                if (workerUid == Process.myUid()) return@withTimeoutOrNull failure(FailureCode.InvalidIdentity)
                if (!authority.accepts(identity)) return@withTimeoutOrNull failure(FailureCode.Revoked)
                val brokerBinder = object : IExecutionBroker.Stub() {
                    override fun call(operation: String, arguments: ByteArray): ByteArray {
                        if (Binder.getCallingUid() != workerUid || finished.get() || !authority.accepts(identity))
                            throw SecurityException("Invalid worker invocation")
                        check(operation.length <= 256 && arguments.size <= IsolatedExecutionService.MAX_IPC_BYTES)
                        check(callingBroker.compareAndSet(false, true)) { "Concurrent bridge call" }
                        try {
                            val host = checkNotNull(broker) { "No broker capability" }
                            val args = BridgeWire.arguments(arguments)
                            val value = runBlocking { withContext(Dispatchers.IO + brokerCalls) { host.call(operation, args) } }
                            val reply = value.toString().toByteArray(Charsets.UTF_8)
                            check(reply.size <= IsolatedExecutionService.MAX_IPC_BYTES && !finished.get() && authority.accepts(identity))
                            return reply
                        } finally { callingBroker.set(false) }
                    }
                }
                service.execute(request, object : IExecutionCallback.Stub() {
                    override fun onResult(bytes: ByteArray) {
                        if (Binder.getCallingUid() != workerUid || finished.get()) return
                        val reply = if (!authority.accepts(identity)) failure(FailureCode.Revoked)
                        else if (bytes.size > IsolatedExecutionService.MAX_IPC_BYTES) failure(FailureCode.OutputLimit)
                        else try {
                            val decoded = ExecutionWire.decodeResult(bytes)
                            if (decoded is ExecutionResult.Success && decoded.output.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes)
                                failure(FailureCode.OutputLimit) else decoded
                        } catch (_: Exception) { failure(FailureCode.InvalidTask) }
                        result.complete(reply)
                    }
                }, brokerBinder)
                select {
                    result.onAwait { it }
                    worker.died.onAwait { failure(FailureCode.ProcessExited) }
                }
            } ?: failure(FailureCode.Timeout)
            val accepted = if (authority.accepts(identity)) completed else failure(FailureCode.Revoked)
            keepWorker = accepted is ExecutionResult.Success && worker.retainsLibraries && !worker.died.isCompleted
            accepted
        } catch (_: RemoteException) {
            failure(FailureCode.ProcessExited)
        } finally {
            finished.set(true)
            brokerCalls.cancel()
            broker?.close()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                revocations.cancelAndJoin()
                if (!keepWorker) retire(worker)
            }
        }
    }

    private fun failure(code: FailureCode) = ExecutionResult.Failure(code)

    companion object {
        private val workerLock = Mutex()
        private var retiringBinder: IBinder? = null
        private var retainedWorker: IsolatedWorkerConnection? = null
    }
}
