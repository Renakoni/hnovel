package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.michaelbull.result.onOk
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import androidx.work.workDataOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@HiltWorker
class CheckUpdateWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bookRepository: BookRepository,
    private val bookshelfRepository: BookshelfRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val reminderBookMap = mutableMapOf<String, BookInformation>()
        val needRemindBookIdSet = mutableSetOf<String>()
        bookshelfRepository
            .getAllBookshelves()
            .filter { it.systemUpdateReminder }
            .forEach {
                needRemindBookIdSet.addAll(it.allBookIds)
            }
        var failedCount = 0
        val outcomes = mutableListOf<kotlinx.serialization.json.JsonObject>()
        bookshelfRepository.getAllBookshelfBooksMetadata().forEach { metadata ->
            if (metadata.id !in needRemindBookIdSet) return@forEach
            delay(3000.milliseconds)
            var status = "unchanged"
            // Preserve legacy Wenku8 bare IDs while canonicalizing new source-qualified keys.
            val book = runCatching { BookIdentity.book(metadata.id) }.getOrNull()
            if (book == null) {
                status = "invalid_book_identity"
            } else try {
                val result = bookRepository.refreshBookInformation(book, WebDataSourcePriority.Low)
                if (result.isErr) status = bookWorkFailureReason(result.component2())
                result.onOk { information ->
                    if (information.lastUpdated.isAfter(metadata.lastUpdate)) {
                        // Repository refresh already updated metadata and bookshelf markers.
                        reminderBookMap[book.storageKey] = information
                        status = "updated"
                    }
                }
            } catch (failure: CancellationException) {
                currentCoroutineContext().ensureActive()
                status = "source_unavailable"
            } catch (failure: Exception) {
                status = "source_request_failed"
            }
            if (status != "updated" && status != "unchanged") failedCount++
            outcomes += buildJsonObject {
                put("bookId", metadata.id)
                put("status", status)
            }
        }
        reminderBookMap.values.forEach {
            with(NotificationManagerCompat.from(appContext)) {
                if (ActivityCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@forEach
                }
                createNotificationChannel()
                notify(
                    "book_update:${it.id}",
                    0,
                    NotificationCompat.Builder(appContext, "BookUpdate")
                        .setSmallIcon(R.drawable.icon_foreground)
                        .setContentTitle(appContext.getString(R.string.app_name))
                        .setContentText("您关注的轻小说 ${it.title} 更新了")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                )
            }
        }
        // Per-target results can exceed Work Data's 10 KB limit. Persist only safe identity
        // and status fields, and return bounded summary counts plus a report filename.
        val report = appContext.filesDir.resolve("book-update-results").apply { mkdirs() }.resolve("$id.json")
        val atomic = android.util.AtomicFile(report)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output!!.write(JsonArray(outcomes).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output!!)
        } catch (failure: Exception) {
            output?.let(atomic::failWrite)
            return Result.failure(workDataOf("reason" to "report_write_failed"))
        }
        return Result.success(workDataOf(
            "checkedCount" to outcomes.size,
            "updatedCount" to reminderBookMap.size,
            "failedCount" to failedCount,
            "report" to report.name,
        ))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "更新提示"
            val descriptionText = "轻小说更新提示"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("BookUpdate", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
