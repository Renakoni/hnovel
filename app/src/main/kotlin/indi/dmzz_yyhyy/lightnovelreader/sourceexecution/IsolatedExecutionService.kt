package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import android.os.ResultReceiver
import hnovel.execution.WorkerMain

/** Non-exported Android shell. isolatedProcess gives the worker a UID without app permissions. */
class IsolatedExecutionService : Service() {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    inner class CommandBinder : Binder() {
        fun execute(request: ByteArray, receiver: ResultReceiver) {
            if (request.size > MAX_IPC_BYTES) {
                receiver.send(0, Bundle().apply { putString("error", "payload_too_large") })
                return
            }
            executor.execute {
                val result = WorkerMain.executeSerialized(request.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                if (result.size > MAX_IPC_BYTES) receiver.send(0, Bundle().apply { putString("error", "result_too_large") })
                else receiver.send(1, Bundle().apply { putByteArray("result", result) })
            }
        }
    }
    private val binder = CommandBinder()
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    companion object { const val MAX_IPC_BYTES = 256 * 1024 }
}
