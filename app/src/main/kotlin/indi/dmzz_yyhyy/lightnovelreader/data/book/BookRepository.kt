package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
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
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Start observing only after KEEP has accepted the submission or retained the active work. */
internal fun WorkManager.observeSubmittedUniqueWork(name: String, operation: Operation): Flow<WorkInfo?> = flow {
    operation.await()
    // These names enqueue a single KEEP request, without APPEND/dependencies. KEEP retains
    // the active request or deletes the terminal record before inserting its replacement.
    emitAll(getWorkInfosForUniqueWorkFlow(name).map { it.singleOrNull() })
}

@Singleton
class BookRepository @Inject constructor(
    private val localBookDataSource: LocalBookDataSource,
    private val bookshelfRepository: BookshelfRepository,
    private val textProcessingRepository: TextProcessingRepository,
    private val workManager: WorkManager,
    private val chapterRepository: ChapterRepository,
    private val readingDataRepository: BookReadingDataRepository,
    private val sourceRegistry: indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry,
): BookRepositoryApi {
    companion object {
        private const val TAG = "BookRepository"
    }


    fun getBookInformationFlow(book: SourceBookId, priority: WebDataSourcePriority = WebDataSourcePriority.Default) =
        getBookInformationFlow(book.storageKey, priority)

    override fun getBookInformationFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookInformation, WebRequestError>> = flow {
        val book = BookIdentity.book(id)
        val local = localBookDataSource.getBookInformation(book.storageKey)
        local?.also {
            emit(Ok(it))
            if (BuildConfig.BENCHMARK) return@flow
        }
        refreshBookInformation(book, priority)
            .also {
                if (it.isOk || local == null) emit(it)
            }
    }.map { result ->
        result.map {
            textProcessingRepository.processBookInformation { it }
        }
    }

    /** Remote-only refresh reports failure even when a local copy exists (background checks). */
    suspend fun refreshBookInformation(book: SourceBookId, priority: WebDataSourcePriority = WebDataSourcePriority.Low): Result<BookInformation, WebRequestError> =
        sourceRegistry.request(book) { it.getBookInformation(book.remoteId, priority) }.map(book::bind)
            .onOk { remote ->
                localBookDataSource.updateBookInformation(remote)
                val bookshelfBookMetadata = bookshelfRepository.getBookshelfBookMetadata(book.storageKey) ?: return@onOk
                if (bookshelfBookMetadata.lastUpdate.isBefore(remote.lastUpdated))
                    bookshelfBookMetadata.bookShelfIds.forEach {
                        bookshelfRepository.updateBookshelfBookMetadataLastUpdateTime(
                            book.storageKey,
                            remote.lastUpdated
                        )
                        bookshelfRepository.addUpdatedBooksIntoBookShelf(it, book.storageKey)
                    }
            }.onErr {
                Log.e(TAG, "Source request failed for ${book.fileKey}: ${it.kind}")
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

    fun cacheBook(bookId: String): Flow<WorkInfo?> {
        val key = BookIdentity.bookKey(bookId)
        val workRequest = OneTimeWorkRequestBuilder<CacheBookWork>()
            .setInputData(
                workDataOf(
                    "bookId" to key
                )
            )
            .build()
        val operation = workManager.enqueueUniqueWork(
            CacheBookWork.ofId(key),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        return workManager.observeSubmittedUniqueWork(CacheBookWork.ofId(key), operation)
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

    suspend fun bookTagPage(book: SourceBookId, tag: String): Result<String?, WebRequestError> =
        sourceRegistry.request(book) { Ok(it.bookTagPage(tag)) }

    suspend fun volumeCover(
        book: SourceBookId,
        volume: Volume,
        chapters: Map<String, ChapterContent>,
        context: Context,
    ): Result<Uri?, WebRequestError> = sourceRegistry.request(book) { runtime ->
        val remoteChapters = chapters.values.map(book::remoteContent).associateBy { it.id }.toMutableMap()
        Ok(runtime.volumeCover(book.remoteId, book.remoteVolume(volume), remoteChapters, context))
    }
}
