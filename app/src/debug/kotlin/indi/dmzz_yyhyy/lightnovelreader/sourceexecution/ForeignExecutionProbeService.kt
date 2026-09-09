package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process

/** Debug-only adversarial fixture: receives a forwarded Binder under a different isolated UID. */
class ForeignExecutionProbeService : Service() {
    private val binder = object : IForeignExecutionProbe.Stub() {
        override fun isRejected(executionService: IBinder): Boolean = try {
            IIsolatedExecutionService.Stub.asInterface(executionService).workerUid()
            false
        } catch (_: SecurityException) { true }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
}
