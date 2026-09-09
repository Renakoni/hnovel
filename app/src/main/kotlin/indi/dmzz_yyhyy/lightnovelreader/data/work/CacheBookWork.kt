package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.coroutines.coroutineBinding
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.download.MutableDownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.last

@HiltWorker
class CacheBookWork @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localBookDataSource: LocalBookDataSource,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val bookRepository: BookRepository
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val TAG = "CacheBookWork"

        fun ofId(id: String): String = "cache:${BookIdentity.bookKey(id)}"
    }

    override suspend fun doWork(): Result {
        val book = inputData.sourceBook() ?: return bookWorkFailure("invalid_book_identity")
        val bookId = book.storageKey
        val downloadItem = MutableDownloadItem(
            DownloadType.CACHE,
            bookId,
            bookRepository.getBookInformationFlow(bookId)
        )
        downloadProgressRepository.addExportItem(downloadItem)
        try {
            val result = bookRepository.getBookVolumesFlow(bookId).last()
                .andThen { bookVolumes ->
                    coroutineBinding {
                        var count = 0
                        val total = bookVolumes.volumes.sumOf { it.chapters.size } + 1
                        localBookDataSource.updateBookVolumes(bookVolumes)
                        bookVolumes.volumes.forEach { volume ->
                            volume.chapters.map { it.id }.forEach { chapterId ->
                                val chapter = bookRepository.getChapterContentFlow(chapterId, bookId).last().bind()
                                localBookDataSource.updateChapterContent(chapter)
                                count ++
                                downloadItem.progress = count.toFloat() / total
                            }
                        }
                    }
                }
                .andThen {
                    coroutineBinding {
                        val bookInformation = bookRepository.getBookInformationFlow(bookId).last().bind()
                        localBookDataSource.updateBookInformation(bookInformation)
                    }
                }
            if (result.isErr) {
                downloadItem.progress = -1f
                return bookWorkFailure(bookWorkFailureReason(result.component2()), book)
            }
            downloadItem.progress = 1f
            return Result.success()
        } catch (failure: CancellationException) {
            downloadItem.progress = -1f
            currentCoroutineContext().ensureActive()
            return bookWorkFailure("source_unavailable", book)
        } catch (failure: Exception) {
            downloadItem.progress = -1f
            Log.e(TAG, "Cache failed for ${book.fileKey}: ${failure.javaClass.simpleName}")
            return bookWorkFailure("cache_failed", book)
        }
    }
}
