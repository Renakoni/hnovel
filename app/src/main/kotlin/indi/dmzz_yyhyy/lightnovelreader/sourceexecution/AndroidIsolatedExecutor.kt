package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** One live worker on API 24+, retired after each invocation. No identity comes from a browser tab. */
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
            invoke(identity, request, limits, broker)
        } finally {
            workerLock.unlock()
        }
    }

    private suspend fun invoke(identity: ExecutionIdentity, request: ByteArray,
        limits: ExecutionLimits, broker: SourceExecutionBroker?): ExecutionResult = coroutineScope {
        val connected = CompletableDeferred<IIsolatedExecutionService>()
        val result = CompletableDeferred<ExecutionResult>()
        val died = CompletableDeferred<Unit>()
        val finished = AtomicBoolean()
        val callingBroker = AtomicBoolean()
        var remote: IIsolatedExecutionService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = IIsolatedExecutionService.Stub.asInterface(binder)
                if (finished.get()) {
                    try { service.terminate() } catch (_: RemoteException) { }
                    return
                }
                try {
                    binder.linkToDeath({
                        died.complete(Unit)
                        result.complete(failure(FailureCode.ProcessExited))
                    }, 0)
                    remote = service
                    connected.complete(service)
                } catch (_: RemoteException) {
                    result.complete(failure(FailureCode.ProcessExited))
                    connected.completeExceptionally(RemoteException())
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                died.complete(Unit)
                result.complete(failure(FailureCode.ProcessExited))
            }
            override fun onNullBinding(name: ComponentName) {
                connected.completeExceptionally(RemoteException())
            }
            override fun onBindingDied(name: ComponentName) {
                connected.completeExceptionally(RemoteException())
                result.complete(failure(FailureCode.ProcessExited))
                died.complete(Unit)
            }
        }
        val bound = context.bindService(Intent(context, IsolatedExecutionService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) return@coroutineScope failure(FailureCode.ProcessExited)
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
                    connected.onAwait { it }
                    result.onAwait { null }
                } ?: return@withTimeoutOrNull result.await()
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
                result.await()
            } ?: failure(FailureCode.Timeout)
            if (authority.accepts(identity)) completed else failure(FailureCode.Revoked)
        } catch (_: RemoteException) {
            failure(FailureCode.ProcessExited)
        } finally {
            finished.set(true)
            brokerCalls.cancel()
            broker?.close()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                revocations.cancelAndJoin()
                // Even successful invocations retire their process; no engine globals survive.
                val service = remote
                retiringBinder = service?.asBinder()
                try {
                    try { service?.terminate() } catch (_: RemoteException) { }
                    if (service != null) withTimeoutOrNull(2000) { died.await() }
                } finally {
                    context.unbindService(connection)
                }
            }
        }
    }

    private fun failure(code: FailureCode) = ExecutionResult.Failure(code)

    companion object {
        private val workerLock = Mutex()
        private var retiringBinder: IBinder? = null
    }
}
