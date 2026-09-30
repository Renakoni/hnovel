package indi.renakoni.nextvol.data.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import indi.renakoni.nextvol.data.book.BookIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class DownloadCancelReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: BookDownloadScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getStringExtra("bookId") ?: return
        val workId = intent.getStringExtra("workId") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { scheduler.dismiss(BookIdentity.book(bookId), workId) }
            catch (failure: Exception) { Log.w("DownloadCancelReceiver", "Could not cancel download: ${failure.javaClass.simpleName}") }
            finally { pending.finish() }
        }
    }

    companion object {
        fun pendingIntent(context: Context, bookId: String, workId: UUID): PendingIntent {
            val intent = Intent(context, DownloadCancelReceiver::class.java)
                .setData(Uri.parse("nextvol://download/$workId"))
                .putExtra("bookId", bookId).putExtra("workId", workId.toString())
            return PendingIntent.getBroadcast(context, workId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
