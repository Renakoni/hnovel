package indi.renakoni.nextvol.sourceexecution

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionWire
import hnovel.execution.ExecutionPayload
import hnovel.execution.FailureCode
import hnovel.execution.WorkerRuntime
import hnovel.rhino.HostBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Binder transport in an isolated UID. Only the installed app's UID can submit work. */
class IsolatedExecutionService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean()
    private val runtime = WorkerRuntime(AndroidArchiveDecoder)
    private val memoryMonitor = Executors.newSingleThreadScheduledExecutor()

    override fun onCreate() {
        super.onCreate()
        // Includes retained library scopes between calls. This is a sampled allocation budget,
        // not a hard cap on process RSS or virtual address space.
        memoryMonitor.scheduleAtFixedRate(::enforceMemoryBudget, 0, 25, TimeUnit.MILLISECONDS)
    }

    private fun enforceMemoryBudget() {
        val vm = Runtime.getRuntime()
        fun allocated() = vm.totalMemory() - vm.freeMemory() + Debug.getNativeHeapAllocatedSize()
        if (allocated() <= MAX_ALLOCATED_BYTES) return
        // Large documents create short-lived decoding buffers. Reclaim them before treating
        // the sampled heap as retained script state; a reachable runaway allocation still dies.
        vm.gc()
        val retained = allocated()
        if (retained > MAX_ALLOCATED_BYTES) {
            android.util.Log.w("SourceExecution", "Worker allocation budget exceeded: $retained bytes")
            Process.killProcess(Process.myPid())
        }
    }

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

        override fun execute(request: ByteArray, callback: IExecutionCallback, broker: IExecutionBroker?) {
            enforceHost()
            if (request.size > ExecutionWire.MAX_INPUT_PACKET_BYTES) {
                deliver(callback, ExecutionWire.encodeResult(ExecutionResult.Failure(FailureCode.InputLimit)))
                return
            }
            if (!running.compareAndSet(false, true)) {
                deliver(callback, ExecutionWire.encodeResult(ExecutionResult.Failure(FailureCode.Busy)))
                return
            }
            executor.execute work@ {
                var released = false
                try {
                    enforceMemoryBudget()
                    val result = try {
                        runtime.executeSerialized(ExecutionPayload.unpack(request, ExecutionWire.MAX_INPUT_BYTES).toString(Charsets.UTF_8), HostBridge { name, args ->
                            val bytes = JsonArray(args).toString().toByteArray(Charsets.UTF_8)
                            check(bytes.size <= MAX_IPC_BYTES) { "Bridge request too large" }
                            val reply = checkNotNull(broker) { "Host broker required" }.call(name, bytes)
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(reply).use {
                                Json.parseToJsonElement(hnovel.execution.BridgeWire.readReply(it))
                            }
                        }).toByteArray(Charsets.UTF_8)
                    } catch (_: OutOfMemoryError) {
                        // Never try to serialize a result or reuse library state after allocation failure.
                        Process.killProcess(Process.myPid())
                        return@work
                    } catch (_: Exception) {
                        ExecutionWire.encodeResult(ExecutionResult.Failure(FailureCode.InvalidTask))
                    }
                    enforceMemoryBudget()
                    // The result permits the next serialized call; mark idle before notifying the host.
                    released = true
                    running.set(false)
                    deliver(callback, result)
                } finally {
                    if (!released) running.set(false)
                }
            }
        }

        override fun terminate() {
            enforceHost()
            Process.killProcess(Process.myPid())
        }
    }

    private fun deliver(callback: IExecutionCallback, result: ByteArray) {
        val packet = if (result.size <= ExecutionWire.MAX_RESULT_BYTES)
            ExecutionPayload.pack(result, ExecutionWire.MAX_RESULT_BYTES) else null
        try {
            if (packet == null) {
                callback.onResult(ExecutionWire.encodeResult(ExecutionResult.Failure(FailureCode.OutputLimit)))
            } else if (packet.size <= MAX_IPC_BYTES) {
                callback.onResult(packet)
            } else {
                val ends = ParcelFileDescriptor.createPipe()
                ends[0].use { read ->
                    ParcelFileDescriptor.AutoCloseOutputStream(ends[1]).use { output ->
                        callback.onResultFile(read)
                        output.write(packet)
                    }
                }
            }
        } catch (_: RemoteException) {
            Process.killProcess(Process.myPid())
        } catch (_: java.io.IOException) {
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
    override fun onDestroy() {
        memoryMonitor.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val MAX_IPC_BYTES = 256 * 1024
        private const val MAX_ALLOCATED_BYTES = 96L * 1024 * 1024
    }
}
