package indi.renakoni.nextvol.data.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.github.michaelbull.result.coroutines.coroutineBinding
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.download.MutableDownloadItem
import indi.renakoni.nextvol.data.download.downloadChapterSignature
import indi.renakoni.nextvol.data.image.SourceImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@HiltWorker
class CacheBookWork @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val bookRepository: BookRepository,
    private val downloads: BookDownloadStore,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val TAG = "CacheBookWork"

        fun ofId(id: String): String = "cache:${BookIdentity.bookKey(id)}"
        fun generationTag(generation: Long): String = "book-download:$generation"
    }

    override suspend fun doWork(): Result {
        val requested = inputData.sourceBook() ?: return bookWorkFailure("invalid_book_identity")
        // Detail resolution may promote a standalone work into a series. Do that before owning files.
        val information = bookRepository.refreshBookInformation(requested, fresh = true)
        val book = bookRepository.canonicalBook(requested)
        if (information.isErr) return downloads.withBookOperation(book) {
            try {
                val attempt = downloads.begin(book, inputData.getLong("downloadGeneration", 0), id.toString())
                downloads.finish(attempt, success = false)
            } catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
            bookWorkFailure(bookWorkFailureReason(information.component2()), book)
        }
        return downloads.withBookOperation(book) { cacheBook(book, information.component1()!!) }
    }

    private suspend fun cacheBook(book: indi.renakoni.nextvol.data.book.SourceBookId,
        information: io.nightfish.lightnovelreader.api.book.BookInformation): Result {
        val item = MutableDownloadItem(DownloadType.CACHE, book.storageKey,
            bookRepository.getBookInformationFlow(book.storageKey))
        downloadProgressRepository.addExportItem(item)
        var attempt: BookDownloadStore.Attempt? = null
        var complete = false
        try {
            // Pre-upgrade queued requests belong to generation zero.
            val active = downloads.begin(book, inputData.getLong("downloadGeneration", 0), id.toString())
            attempt = active
            val revision = bookRepository.sourceRevision(book)
            val result = coroutineBinding {
                val volumes = bookRepository.downloadDirectory(book).bind()
                val chapters = volumes.volumes.flatMap { it.chapters }.distinctBy { it.id }
                check(chapters.isNotEmpty()) { "Source returned an empty directory" }
                val cover = information.coverUri.toString()
                val unchanged = downloads.target(active, volumes, revision, cover)
                val fetchedImages = mutableSetOf<String>()
                chapters.forEachIndexed { index, chapter ->
                    currentCoroutineContext().ensureActive()
                    val signature = downloadChapterSignature(chapters, index, revision)
                    val saved = downloads.reusable(active, chapter.id, signature)
                    val content = saved ?: bookRepository.downloadChapter(book, chapter.id).bind()
                    val images = downloads.chapterImages(content)
                    for (uri in images) {
                        if (uri !in fetchedImages && (saved == null || !downloads.hasImage(active, uri))) {
                            cacheImage(active, SourceImage(book, uri), force = saved == null || downloads.isImageStale(active, uri, false))
                            fetchedImages += uri
                        }
                    }
                    downloads.saveChapter(active, content, signature, images)
                    item.progress = (index + 1f) / (chapters.size + 1)
                }
                if (cover.isNotEmpty() && (!unchanged || !downloads.hasImage(active, cover, true)))
                    cacheImage(active, SourceImage(book, cover, cover = true), force = !unchanged || downloads.isImageStale(active, cover, true))
                check(bookRepository.sourceRevision(book) == revision) { "Source changed during download" }
            }
            if (result.isErr) {
                item.sourceError = result.component2()?.kind
                return bookWorkFailure(bookWorkFailureReason(result.component2()), book)
            }
            downloads.finish(active, success = true)
            complete = true
            item.progress = 1f
            return Result.success()
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            return bookWorkFailure("source_unavailable", book)
        } catch (failure: Exception) {
            Log.e(TAG, "Download failed for ${book.fileKey}: ${failure.javaClass.simpleName}")
            return bookWorkFailure("cache_failed", book)
        } finally {
            if (!complete) {
                item.progress = -1f
                attempt?.let { active -> withContext(NonCancellable) {
                    try { downloads.finish(active, success = false) }
                    catch (_: CancellationException) { /* Cleared or replaced; do not recreate ownership. */ }
                    catch (failure: Exception) { Log.e(TAG, "Could not save download state: ${failure.javaClass.simpleName}") }
                } }
            }
        }
    }

    private suspend fun cacheImage(attempt: BookDownloadStore.Attempt, image: SourceImage, force: Boolean) {
        val request = ImageRequest.Builder(applicationContext)
            .data(image.copy(preferDownloaded = false))
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(if (force) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
            .build()
        when (val result = SingletonImageLoader.get(applicationContext).execute(request)) {
            is ErrorResult -> throw result.throwable
            is SuccessResult -> downloads.retainImage(attempt, image, result.diskCacheKey)
        }
    }
}
