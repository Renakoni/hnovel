package indi.renakoni.nextvol.data.book

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
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.SourceDiscoveryTarget
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.download.BookDownloadStore
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
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Start observing only after KEEP has accepted the submission or retained the active work. */
internal fun WorkManager.observeSubmittedUniqueWork(name: String, operation: Operation): Flow<WorkInfo?> = flow {
    operation.await()
    // These names enqueue a single KEEP request, without APPEND/dependencies. KEEP retains
    // the active request or deletes the terminal record before inserting its replacement.
    emitAll(getWorkInfosForUniqueWorkFlow(name).map { it.singleOrNull() })
}

data class BookReadingAvailability(val online: Boolean, val local: Boolean, val metadataOnly: Boolean,
    val generation: Long?) {
    val available get() = online || local
}

@Singleton
class BookRepository @Inject constructor(
    private val localBookDataSource: LocalBookDataSource,
    private val bookshelfRepository: BookshelfRepository,
    private val textProcessingRepository: TextProcessingRepository,
    private val workManager: WorkManager,
    private val chapterRepository: ChapterRepository,
    private val readingDataRepository: BookReadingDataRepository,
    private val sourceRegistry: indi.renakoni.nextvol.data.web.WebSourceRegistry,
    private val downloads: BookDownloadStore,
): BookRepositoryApi {
    companion object {
        private const val TAG = "BookRepository"
    }

    /** Declared reading capabilities and saved directories are separate from source availability.
     * A disabled source can still supply offline chapters; a metadata-only source cannot supply a TOC. */
    fun readingAvailability(bookId: String): Flow<BookReadingAvailability> {
        val book = BookIdentity.book(bookId)
        return sourceRegistry.sources.map { sources ->
            val entry = sources.find { it.metadata.id == book.sourceId }
            val capabilities = entry?.metadata?.capabilities.orEmpty()
            val online = entry?.metadata?.supportsReading == true &&
                entry?.status != indi.renakoni.nextvol.data.web.SourceStatus.Failed
            BookReadingAvailability(online,
                !online && localBookDataSource.getBookVolumes(book.storageKey)?.volumes?.any { it.chapters.isNotEmpty() } == true,
                entry != null && indi.renakoni.nextvol.data.web.SourceCapability.Directory !in capabilities,
                entry?.generation)
        }.distinctUntilChanged()
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
    suspend fun refreshBookInformation(book: SourceBookId, priority: WebDataSourcePriority = WebDataSourcePriority.Low, fresh: Boolean = false): Result<BookInformation, WebRequestError> =
        sourceRegistry.request(book) { it.getBookInformation(book.remoteId, priority, refresh = fresh) }.map(book::bind)
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
        val generation = downloads.generation()
        val workRequest = OneTimeWorkRequestBuilder<CacheBookWork>()
            .addTag(CacheBookWork.generationTag(generation))
            .setInputData(
                workDataOf(
                    "bookId" to key,
                    "downloadGeneration" to generation,
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

    internal fun sourceRevision(book: SourceBookId): String = sourceRegistry.sources.value
        .firstOrNull { it.metadata.id == book.sourceId }?.metadata?.revision.orEmpty()

    fun downloadChanges(bookId: String) = downloads.observe(BookIdentity.book(bookId))

    suspend fun downloadState(bookId: String, active: Boolean = false) = BookIdentity.book(bookId).let { book ->
        downloads.state(book, localBookDataSource.getBookVolumes(book.storageKey), sourceRevision(book), active)
    }

    /** Download refresh must report remote failures even when the reader can keep showing local content. */
    internal suspend fun downloadDirectory(book: SourceBookId): Result<BookVolumes, WebRequestError> =
        sourceRegistry.request(book) { it.getBookVolumes(book.remoteId, WebDataSourcePriority.Low, refresh = true) }.map(book::bind)
            .onOk { if (it.volumes.any { volume -> volume.chapters.isNotEmpty() }) localBookDataSource.updateBookVolumes(it) }

    internal suspend fun downloadChapter(book: SourceBookId, chapterId: String): Result<ChapterContent, WebRequestError> {
        val chapter = BookIdentity.chapter(chapterId, book)
        return sourceRegistry.request(book) { it.getChapterContent(chapter.remoteId, book.remoteId, WebDataSourcePriority.Low, refresh = true) }
            .map(chapter::bind)
    }

    suspend fun bookTagPage(book: SourceBookId, tag: String): Result<SourceDiscoveryTarget?, WebRequestError> =
        sourceRegistry.request(book) { runtime ->
            Ok(runtime.bookTagPage(tag)?.let { SourceDiscoveryTarget(book.sourceId, it) })
        }

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
