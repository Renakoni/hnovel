package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.util.Log
import indi.dmzz_yyhyy.lightnovelreader.data.reading.ReaderRecordStore
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.ReadingStatsUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/** Records reader events; the owner supplies the existing scopes and live progress inputs. */
internal class ReaderReadingRecords(
    private val store: ReaderRecordStore,
    private val scope: CoroutineScope,
    private val statisticsScope: CoroutineScope,
    private val currentBookId: () -> String,
    private val currentChapterTitle: () -> String?,
    private val chapterCount: suspend (String) -> Int,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val totalReadingTimeMutex = Mutex()
    private val accumulatedReadingTimeLock = Any()
    private var accumulatedReadingTimeJob: Job? = null

    fun openBook(bookId: String) {
        scope.launch(ioDispatcher) {
            store.updateRecentBooks {
                val newList = it.toMutableList()
                if (it.contains(bookId))
                    newList.remove(bookId)
                newList.add(bookId)
                newList
            }
        }
        scope.launch(ioDispatcher) {
            store.updateReadingStatistics(ReadingStatsUpdate(bookId = bookId, readEventDelta = 1))
        }
    }

    fun saveProgress(chapterId: String, progress: Float) {
        val bookId = currentBookId()
        if (progress.isNaN() || progress <= 0f || bookId.isBlank()) return
        val title = currentChapterTitle() ?: return
        scope.launch(ioDispatcher) {
            val currentTime = now()
            // Resolve the count after the event has been queued and bind it to the
            // captured book. The UI's current-book state may have changed by now.
            val total = chapterCount(bookId)

            store.updateUserReadingData(bookId) { userReadingData ->
                Log.v("ReaderViewModel", "$bookId/$chapterId Saving progress $progress. ($title)")
                val readingProgress = if (total > 0) {
                    (userReadingData.maxChapterReadingProgressMap.values.sum() / total).coerceIn(0f, 1f)
                } else {
                    userReadingData.readingProgress
                }
                userReadingData.copyWithUpdatedChapterReadingProgress(chapterId, progress).copy(
                    lastReadTime = currentTime,
                    lastReadChapterId = chapterId,
                    lastReadChapterTitle = title,
                    readingProgress = readingProgress,
                )
            }
            val readingData = store.getUserReadingData(bookId)
            if (readingData.readingProgress >= 1f) {
                store.markBookFinished(bookId)
            }
        }
    }

    fun updateTotalReadingTime(bookId: String, seconds: Int) {
        scope.launch(ioDispatcher) {
            totalReadingTimeMutex.withLock {
                store.updateUserReadingData(bookId) {
                    it.copy(lastReadTime = now(), totalReadTime = it.totalReadTime + seconds)
                }
            }
        }
    }

    fun accumulateReadingTime(bookId: String, seconds: Int) {
        if (bookId.isBlank()) return
        synchronized(accumulatedReadingTimeLock) {
            val previous = accumulatedReadingTimeJob
            accumulatedReadingTimeJob = statisticsScope.launch(ioDispatcher) {
                previous?.join()
                // Negative values remain the statistics repository's existing flush command.
                store.accumulateBookReadTime(bookId, seconds)
            }
        }
    }
}
