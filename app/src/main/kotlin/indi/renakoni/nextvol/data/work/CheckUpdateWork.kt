package indi.renakoni.nextvol.data.work

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
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import androidx.work.workDataOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority

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
        val targets = bookshelfRepository.getAllBookshelfBooksMetadata()
            .filter { it.id in needRemindBookIdSet }
        val outcomes = arrayOfNulls<kotlinx.serialization.json.JsonObject>(targets.size)
        val ready = ArrayDeque(targets.withIndex().groupBy {
            runCatching { BookIdentity.book(it.value.id).sourceId }.getOrNull()
        }.values.map { ArrayDeque(it) })
        val scheduling = Mutex()
        coroutineScope {
            // A source owns at most one active refresh. Return it to the end of the
            // ready queue after each book so a large shelf cannot monopolize a lane.
            repeat(minOf(MAX_ACTIVE_SOURCES, ready.size)) {
                launch {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val sourceBooks = scheduling.withLock { ready.removeFirstOrNull() } ?: break
                        val (index, metadata) = sourceBooks.removeFirst()
                        var status = "unchanged"
                        var updatedInformation: BookInformation? = null
                        // Each metadata entry owns its source; browsing never supplies a fallback.
                        val book = runCatching { BookIdentity.book(metadata.id) }.getOrNull()
                        if (book == null) {
                            status = "invalid_book_identity"
                        } else try {
                            val result = bookRepository.refreshBookInformation(book, WebDataSourcePriority.Low)
                            if (result.isErr) status = bookWorkFailureReason(result.component2())
                            result.onOk { information ->
                                if (information.lastUpdated.isAfter(metadata.lastUpdate)) {
                                    // Repository refresh already updated metadata and bookshelf markers.
                                    updatedInformation = information
                                    status = "updated"
                                }
                            }
                        } catch (failure: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            status = "source_unavailable"
                        } catch (failure: Exception) {
                            status = "source_request_failed"
                        }
                        scheduling.withLock {
                            updatedInformation?.let { reminderBookMap[checkNotNull(book).storageKey] = it }
                            if (status != "updated" && status != "unchanged") failedCount++
                            outcomes[index] = buildJsonObject {
                                put("bookId", metadata.id)
                                put("status", status)
                            }
                            if (sourceBooks.isNotEmpty()) ready.addLast(sourceBooks)
                        }
                    }
                }
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
                        .setContentText(appContext.getString(R.string.book_update_notification, it.title))
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
            output!!.write(JsonArray(outcomes.map { checkNotNull(it) }).toString().toByteArray(Charsets.UTF_8))
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
            val name = appContext.getString(R.string.book_update_channel)
            val descriptionText = appContext.getString(R.string.book_update_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("BookUpdate", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val MAX_ACTIVE_SOURCES = 8
    }
}
