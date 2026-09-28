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
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.downloadFailure
import io.nightfish.lightnovelreader.api.error.WebRequestError
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
        var task: BookDownloadStore.Task? = null
        try {
            val initial = bookRepository.canonicalBook(requested)
            task = downloads.startTask(initial, inputData.getLong("downloadGeneration", 0), id.toString(),
                runAttemptCount, legacy = !inputData.getBoolean("persistedTask", false))
            // Record the task before details; identity promotion transfers it without owning files yet.
            val information = bookRepository.refreshBookInformation(initial, fresh = true)
            val book = bookRepository.canonicalBook(initial)
            val resolvedTask = task.copy(book = book)
            task = resolvedTask
            if (information.isErr) return downloads.withBookOperation(book) {
                val attempt = downloads.begin(book, resolvedTask.generation, id.toString(), requireTask = true)
                downloads.finish(attempt, success = false)
                downloads.finishTask(resolvedTask, downloadFailure(information.component2(), DownloadStage.Details))
                bookWorkFailure(bookWorkFailureReason(information.component2()), book)
            }
            return downloads.withBookOperation(book) { cacheBook(book, information.component1()!!, resolvedTask) }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            return bookWorkFailure("source_unavailable", requested)
        } catch (failure: Exception) {
            task?.let { downloads.finishTask(it, downloadFailure(WebRequestError("", "", failure), DownloadStage.Details)) }
            Log.e(TAG, "Download details failed: ${failure.javaClass.simpleName}")
            return bookWorkFailure("cache_failed", requested)
        }
    }

    private suspend fun cacheBook(book: indi.renakoni.nextvol.data.book.SourceBookId,
        information: io.nightfish.lightnovelreader.api.book.BookInformation, task: BookDownloadStore.Task): Result {
        val item = MutableDownloadItem(DownloadType.CACHE, book.storageKey,
            bookRepository.getBookInformationFlow(book.storageKey))
        downloadProgressRepository.addExportItem(item)
        var attempt: BookDownloadStore.Attempt? = null
        var complete = false
        var stage = DownloadStage.Directory
        suspend fun mark(value: DownloadStage, chapter: String = "") {
            stage = value
            downloads.taskStage(task, value, chapter)
        }
        try {
            // Pre-upgrade queued requests belong to generation zero.
            val active = downloads.begin(book, inputData.getLong("downloadGeneration", 0), id.toString(), requireTask = true)
            attempt = active
            val revision = bookRepository.sourceRevision(book)
            val result = coroutineBinding {
                mark(DownloadStage.Directory)
                val volumes = bookRepository.downloadDirectory(book).bind()
                val chapters = volumes.volumes.flatMap { it.chapters }.distinctBy { it.id }
                check(chapters.isNotEmpty()) { "Source returned an empty directory" }
                val cover = information.coverUri.toString()
                mark(DownloadStage.Storage)
                val unchanged = downloads.target(active, volumes, revision, cover)
                val fetchedImages = mutableSetOf<String>()
                chapters.forEachIndexed { index, chapter ->
                    currentCoroutineContext().ensureActive()
                    val signature = downloadChapterSignature(chapters, index, revision)
                    val saved = downloads.reusable(active, chapter.id, signature)
                    mark(DownloadStage.Body, chapter.id)
                    val content = saved ?: bookRepository.downloadChapter(book, chapter.id).bind()
                    val images = downloads.chapterImages(content)
                    for (uri in images) {
                        if (uri !in fetchedImages && (saved == null || !downloads.hasImage(active, uri))) {
                            mark(DownloadStage.Image, chapter.id)
                            cacheImage(active, SourceImage(book, uri), force = saved == null || downloads.isImageStale(active, uri, false)) {
                                mark(DownloadStage.Storage, chapter.id)
                            }
                            fetchedImages += uri
                        }
                    }
                    mark(DownloadStage.Storage, chapter.id)
                    downloads.saveChapter(active, content, signature, images)
                    item.progress = (index + 1f) / (chapters.size + 1)
                }
                if (cover.isNotEmpty() && (!unchanged || !downloads.hasImage(active, cover, true))) {
                    mark(DownloadStage.Cover)
                    cacheImage(active, SourceImage(book, cover, cover = true), force = !unchanged || downloads.isImageStale(active, cover, true)) {
                        mark(DownloadStage.Storage)
                    }
                }
            }
            if (result.isErr) {
                item.sourceError = result.component2()?.kind
                downloads.finishTask(task, downloadFailure(result.component2(), stage))
                return bookWorkFailure(bookWorkFailureReason(result.component2()), book)
            }
            if (bookRepository.sourceRevision(book) != revision) {
                mark(DownloadStage.Details)
                downloads.finishTask(task, DownloadFailure.SourceUnavailable)
                return bookWorkFailure("source_unavailable", book)
            }
            downloads.finish(active, success = true)
            downloads.finishTask(task)
            complete = true
            item.progress = 1f
            return Result.success()
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            return bookWorkFailure("source_unavailable", book)
        } catch (failure: Exception) {
            downloads.finishTask(task, downloadFailure(WebRequestError("", "", failure), stage))
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

    private suspend fun cacheImage(attempt: BookDownloadStore.Attempt, image: SourceImage, force: Boolean,
        beforeSave: suspend () -> Unit) {
        val request = ImageRequest.Builder(applicationContext)
            .data(image.copy(preferDownloaded = false))
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(if (force) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
            .build()
        when (val result = SingletonImageLoader.get(applicationContext).execute(request)) {
            is ErrorResult -> throw result.throwable
            is SuccessResult -> {
                beforeSave()
                downloads.retainImage(attempt, image, result.diskCacheKey)
            }
        }
    }
}
