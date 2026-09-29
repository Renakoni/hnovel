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
import com.github.michaelbull.result.Err
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.DownloadRetryPolicy
import hnovel.network.RequestRetryContext
import indi.renakoni.nextvol.data.download.downloadFailure
import io.nightfish.lightnovelreader.api.error.WebRequestError
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.download.MutableDownloadItem
import indi.renakoni.nextvol.data.download.downloadChapterSignature
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.web.SourceRequestVersion
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
    internal var retryPolicy = DownloadRetryPolicy()
    companion object {
        private const val TAG = "CacheBookWork"

        fun ofId(id: String): String = "cache:${BookIdentity.bookKey(id)}"
        fun generationTag(generation: Long): String = "book-download:$generation"
    }

    override suspend fun doWork(): Result {
        val requested = inputData.sourceBook() ?: return bookWorkFailure("invalid_book_identity")
        return runDownload(requested)
    }

    private suspend fun runDownload(requested: indi.renakoni.nextvol.data.book.SourceBookId): Result {
        var task: BookDownloadStore.Task? = null
        try {
            val initial = bookRepository.canonicalBook(requested)
            val startedTask = downloads.startTask(initial, inputData.getLong("downloadGeneration", 0), id.toString(),
                runAttemptCount, legacy = !inputData.getBoolean("persistedTask", false), nowMillis = retryPolicy.nowMillis())
            task = startedTask
            val owner = checkNotNull(downloads.entry(initial))
            val source = bookRepository.downloadSource(initial)
            if (owner.taskAccountGeneration >= 0 &&
                (source?.accountGeneration != owner.taskAccountGeneration || source.revision != owner.taskSourceRevision)) {
                downloads.finishTask(task, DownloadFailure.SourceUnavailable)
                return bookWorkFailure("source_unavailable", initial)
            }
            if (owner.taskNextAttemptAt > retryPolicy.nowMillis()) return Result.retry()
            if (source != null) downloads.bindTaskSource(task, source.revision, source.accountGeneration)
            val retry = if (bookRepository.canReplayDownload(initial)) RequestRetryContext() else null
            val version = source?.let(::SourceRequestVersion)
            version?.check(bookRepository.downloadSource(initial))
            return withContext((retry ?: kotlin.coroutines.EmptyCoroutineContext) +
                (version ?: kotlin.coroutines.EmptyCoroutineContext)) {
                // Record the task before details; identity promotion transfers it without owning files yet.
                val information = bookRepository.refreshBookInformation(initial, fresh = true)
                val book = bookRepository.canonicalBook(initial)
                val resolvedTask = startedTask.copy(book = book)
                task = resolvedTask
                if (information.isErr) return@withContext downloads.withBookOperation(book) {
                    val attempt = downloads.begin(book, resolvedTask.generation, id.toString(), requireTask = true)
                    downloads.finish(attempt, success = false)
                    failed(resolvedTask, information.component2(), DownloadStage.Details)
                }
                downloads.withBookOperation(book) { cacheBook(book, information.component1()!!, resolvedTask) }
            }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            return bookWorkFailure("source_unavailable", requested)
        } catch (failure: Exception) {
            task?.let { downloads.finishTask(it, downloadFailure(WebRequestError("", "", failure), DownloadStage.Details)) }
            Log.e(TAG, "Download details failed: ${failure.javaClass.simpleName}")
            return bookWorkFailure("cache_failed", requested)
        }
    }

    private suspend fun failed(task: BookDownloadStore.Task, error: WebRequestError?, stage: DownloadStage): Result {
        val retryContext = currentCoroutineContext()[RequestRetryContext]
        val hint = when (val cause = error?.throwable) {
            is hnovel.content.SourceContentException -> cause.retry
            is indi.renakoni.nextvol.data.image.SourceImageRequestException -> cause.retry
            else -> null
        }
        val owner = downloads.entry(task.book)
        val source = bookRepository.downloadSource(task.book)
        val currentSource = owner != null && source != null && owner.taskSourceRevision == source.revision &&
            owner.taskAccountGeneration == source.accountGeneration
        val failure = if (owner != null && owner.taskAccountGeneration >= 0 && !currentSource)
            DownloadFailure.SourceUnavailable else downloadFailure(error, stage)
        if (retryContext?.replaySafe == true && hint != null && currentSource &&
            failure in setOf(DownloadFailure.Network, DownloadFailure.RateLimited)) {
            val next = retryPolicy.nextAttemptAt(checkNotNull(owner).taskRetryCount, hint)
            if (next != null) {
                downloads.deferTaskRetry(task, owner.taskRetryCount, next, failure)
                return Result.retry()
            }
            downloads.finishTask(task, DownloadFailure.RetryExhausted)
        } else downloads.finishTask(task, failure)
        return bookWorkFailure(bookWorkFailureReason(error), task.book)
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
            currentCoroutineContext()[SourceRequestVersion]?.check(bookRepository.downloadSource(book))
            stage = value
            downloads.taskStage(task, value, chapter)
        }
        try {
            // Pre-upgrade queued requests belong to generation zero.
            val active = downloads.begin(book, inputData.getLong("downloadGeneration", 0), id.toString(), requireTask = true)
            attempt = active
            val revision = bookRepository.sourceRevision(book)
            val result = coroutineBinding<Unit, WebRequestError> {
                mark(DownloadStage.Directory)
                val volumes = bookRepository.downloadDirectory(book).bind()
                val chapters = volumes.volumes.flatMap { it.chapters }.distinctBy { it.id }
                check(chapters.isNotEmpty()) { "Source returned an empty directory" }
                val cover = information.coverUri.toString()
                mark(DownloadStage.Storage)
                val unchanged = downloads.target(active, volumes, revision, cover)
                val fetchedImages = mutableSetOf<String>()
                // The static-rule replay gate excludes scripts and shared book variables.
                // Request bodies/method overrides may carry sequencing semantics even without JS.
                val independent = bookRepository.canReplayDownload(book) && chapters.none {
                    Regex("\"(?:method|body)\"\\s*:", RegexOption.IGNORE_CASE)
                        .containsMatchIn(BookIdentity.chapter(it.id, book).remoteId)
                }
                var chapterFailure: Triple<WebRequestError, DownloadStage, String>? = null
                chapters.forEachIndexed { index, chapter ->
                    currentCoroutineContext().ensureActive()
                    val signature = downloadChapterSignature(chapters, index, revision)
                    val chapterResult = try {
                        coroutineBinding<Unit, WebRequestError> {
                            mark(DownloadStage.Body, chapter.id)
                            val checkpoint = downloads.checkpoint(active, chapter.id, signature) ?: run {
                                val content = bookRepository.downloadChapter(book, chapter.id).bind()
                                mark(DownloadStage.Storage, chapter.id)
                                downloads.saveCandidate(active, content, signature)
                            }
                            for (uri in checkpoint.images) {
                                if (!downloads.hasCheckpointImage(active, checkpoint, uri)) {
                                    mark(DownloadStage.Image, chapter.id)
                                    cacheImage(active, SourceImage(book, uri), force = uri !in fetchedImages,
                                        resourceVersion = checkpoint.resourceVersion) {
                                        mark(DownloadStage.Storage, chapter.id)
                                    }
                                    fetchedImages += uri
                                }
                            }
                            mark(DownloadStage.Storage, chapter.id)
                            downloads.publish(active, checkpoint)
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { Err(WebRequestError("", "", failure)) }
                    if (chapterResult.isErr) {
                        val error = checkNotNull(chapterResult.component2())
                        val contentError = error.throwable as? hnovel.content.SourceContentException
                        val imageError = error.throwable as? indi.renakoni.nextvol.data.image.SourceImageRequestException
                        val isolated = stage in setOf(DownloadStage.Body, DownloadStage.Image) &&
                            ((contentError?.httpStatus ?: imageError?.httpStatus) == 404 ||
                                contentError?.code == hnovel.content.ContentError.EmptyContent)
                        if (!independent || !isolated) chapterResult.bind()
                        if (chapterFailure == null) chapterFailure = Triple(error, stage, chapter.id)
                    }
                    item.progress = (index + 1f) / (chapters.size + 1)
                }
                if (cover.isNotEmpty() && (!unchanged || !downloads.hasImage(active, cover, true))) {
                    mark(DownloadStage.Cover)
                    cacheImage(active, SourceImage(book, cover, cover = true), force = !unchanged || downloads.isImageStale(active, cover, true)) {
                        mark(DownloadStage.Storage)
                    }
                }
                chapterFailure?.let { (error, failedStage, chapterId) ->
                    mark(failedStage, chapterId)
                    Err(error).bind()
                }
                Unit
            }
            if (result.isErr) {
                item.sourceError = result.component2()?.kind
                return failed(task, result.component2(), stage)
            }
            currentCoroutineContext()[SourceRequestVersion]?.check(bookRepository.downloadSource(book))
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
            Log.e(TAG, "Download failed for ${book.fileKey}: ${failure.javaClass.simpleName}")
            return failed(task, WebRequestError("", "", failure), stage)
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
        resourceVersion: String = "",
        beforeSave: suspend () -> Unit) {
        val request = ImageRequest.Builder(applicationContext)
            .data(image.copy(preferDownloaded = false))
            .coroutineContext((currentCoroutineContext()[RequestRetryContext] ?: kotlin.coroutines.EmptyCoroutineContext) +
                (currentCoroutineContext()[SourceRequestVersion] ?: kotlin.coroutines.EmptyCoroutineContext))
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(if (force) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
            .build()
        when (val result = SingletonImageLoader.get(applicationContext).execute(request)) {
            is ErrorResult -> throw result.throwable
            is SuccessResult -> {
                beforeSave()
                downloads.retainImage(attempt, image, result.diskCacheKey, resourceVersion)
            }
        }
    }
}
