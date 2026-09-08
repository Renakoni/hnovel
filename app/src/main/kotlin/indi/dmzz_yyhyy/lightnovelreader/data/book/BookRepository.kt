package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.util.Log
import androidx.navigation.NavController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val WORK_SUBMISSION_TAG_PREFIX = "lightnovelreader:work-submission:"
private val nextWorkSubmission = AtomicLong(System.currentTimeMillis())

internal fun nextWorkSubmissionTag(): String =
    WORK_SUBMISSION_TAG_PREFIX + nextWorkSubmission.incrementAndGet()

/** Selects an active work first, otherwise the terminal work with the greatest submission tag. */
internal fun selectLatestWorkInfo(workInfos: List<WorkInfo>): WorkInfo? {
    fun WorkInfo.isActive() = state == WorkInfo.State.ENQUEUED ||
        state == WorkInfo.State.RUNNING ||
        state == WorkInfo.State.BLOCKED

    return workInfos.firstOrNull { it.isActive() }
        ?: workInfos.asSequence()
            .filterNot { it.isActive() }
            .maxWithOrNull(
                compareBy<WorkInfo> {
                    it.tags.mapNotNull { tag ->
                        tag.removePrefix(WORK_SUBMISSION_TAG_PREFIX)
                            .takeIf { value -> tag.startsWith(WORK_SUBMISSION_TAG_PREFIX) }
                            ?.toLongOrNull()
                    }.maxOrNull() ?: Long.MIN_VALUE
                }.thenBy { it.generation }
                    .thenBy { it.runAttemptCount }
                    .thenBy { it.id.toString() }
            )
}

@Singleton
class BookRepository @Inject constructor(
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val localBookDataSource: LocalBookDataSource,
    private val bookshelfRepository: BookshelfRepository,
    private val textProcessingRepository: TextProcessingRepository,
    private val workManager: WorkManager,
    private val chapterRepository: ChapterRepository,
    private val readingDataRepository: BookReadingDataRepository
): BookRepositoryApi {
    companion object {
        private const val TAG = "BookRepository"
    }

    private val webBookDataSource get() = webBookDataSourceProvider.value

    override fun getBookInformationFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookInformation, WebRequestError>> = flow {
        val local = localBookDataSource.getBookInformation(id)
        local?.also {
            emit(Ok(it))
            if (BuildConfig.BENCHMARK) return@flow
        }
        webBookDataSource.getBookInformation(id, priority)
            .onOk { remote ->
                localBookDataSource.updateBookInformation(remote)
                val bookshelfBookMetadata = bookshelfRepository.getBookshelfBookMetadata(remote.id) ?: return@onOk
                if (bookshelfBookMetadata.lastUpdate.isBefore(remote.lastUpdated))
                    bookshelfBookMetadata.bookShelfIds.forEach {
                        bookshelfRepository.updateBookshelfBookMetadataLastUpdateTime(
                            remote.id,
                            remote.lastUpdated
                        )
                        bookshelfRepository.addUpdatedBooksIntoBookShelf(it, id)
                    }
            }.onErr {
                Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
                it.throwable?.printStackTrace()
            }
            .also {
                if (it.isOk || local == null) emit(it)
            }
    }.map { result ->
        result.map {
            textProcessingRepository.processBookInformation { it }
        }
    }

    override fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookVolumes, WebRequestError>> = chapterRepository.getBookVolumesFlow(id, priority)

    override fun getChapterContentFlow(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ): Flow<Result<ChapterContent, WebRequestError>> =
        chapterRepository.getChapterContentFlow(chapterId, bookId, priority)

    override suspend fun preloadChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ) = chapterRepository.preloadChapterContent(chapterId, bookId, priority)

    override suspend fun getUserReadingData(bookId: String): UserReadingData =
        readingDataRepository.getUserReadingData(bookId)

    override fun getUserReadingDataFlow(bookId: String): Flow<UserReadingData> =
        readingDataRepository.getUserReadingDataFlow(bookId)

    override suspend fun getAllUserReadingData(): List<UserReadingData> =
        readingDataRepository.getAllUserReadingData()

    override suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) =
        readingDataRepository.updateUserReadingData(id, update)

    fun isCacheBookWorkFlow(bookId: String): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(CacheBookWork.ofId(bookId)).map { workInfos ->
            selectLatestWorkInfo(workInfos)
        }

    fun cacheBook(bookId: String): OneTimeWorkRequest {
        val workRequest = OneTimeWorkRequestBuilder<CacheBookWork>()
            .addTag(nextWorkSubmissionTag())
            .setInputData(
                workDataOf(
                    "bookId" to bookId
                )
            )
            .build()
        workManager.enqueueUniqueWork(
            CacheBookWork.ofId(bookId),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        return workRequest
    }

    override suspend fun getIsBookCached(bookId: String): Boolean {
        localBookDataSource.getBookVolumes(bookId)?.let { bookVolumes ->
            if (bookVolumes.volumes.isEmpty())
                return false
            bookVolumes.volumes.forEach { bookVolume ->
                bookVolume.chapters.forEach {
                    if (!localBookDataSource.isChapterContentExists(it.id))
                        return false
                }
            }
        } ?: return false
        return true
    }

    override fun progressBookTagClick(tag: String, navController: NavController) =
        webBookDataSource.progressBookTagClick(tag, navController)
}
