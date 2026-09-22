package indi.renakoni.nextvol.data.work

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.download.MutableDownloadItem
import indi.renakoni.nextvol.data.download.downloadChapterSignature
import indi.renakoni.nextvol.data.download.downloadHash
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.ui.book.detail.EpubShareActivity
import indi.renakoni.nextvol.ui.book.detail.ExportType
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import indi.renakoni.nextvol.utils.network.ImageDownloader
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.potatoepub.builder.ChapterBuilder
import io.nightfish.potatoepub.builder.EpubBuilder
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.dom4j.Element

@HiltWorker
class ExportBookToEPUBWork @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bookRepository: BookRepository,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val contentJsonDecoder: ContentJsonDecoder,
    private val downloads: BookDownloadStore,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        fun ofId(id: String): String = "export_to_epub:${BookIdentity.bookKey(id)}"
        private const val TAG = "ExportEPUB"
    }

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var notification: Notification? = null
    private var includeImages = true
    private var activeDownloadItem: MutableDownloadItem? = null

    private var totalChapters = 0
    private var processedChapters = 0
    private var currentVolumeTitle = ""
    private var currentChapterTitle = ""

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "BookEpubExport",
                applicationContext.getString(R.string.epub_export_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showProgressNotification() {
        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(applicationContext.getString(R.string.epub_export_notification_preparing))
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateFailureNotification(bookId: String) {

        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(failureMessage())
            .setStyle(NotificationCompat.BigTextStyle().bigText(failureMessage()))
            .setSmallIcon(R.drawable.file_export_24px)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()

        postNotification(ofId(bookId), 0)
    }

    private fun updateCompletionNotification(bookId: String) {
        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(applicationContext.getString(R.string.epub_export_share_ready))
            .setContentIntent(PendingIntent.getActivity(applicationContext, 0,
                EpubShareActivity.intent(applicationContext, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()

        postNotification(ofId(bookId), 0)
    }

    private fun buildProgressNotification(
        progress: Int,
        stage: String
    ) {
        val text = "$stage $currentVolumeTitle / $currentChapterTitle"

        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: "") + " ($progress%)")
            .setContentText(text)
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(100, progress, false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        postNotification(null, id.hashCode())
    }

    private fun postNotification(tag: String?, notificationId: Int) {
        try { notificationManager.notify(tag, notificationId, notification) }
        catch (_: SecurityException) { Log.w(TAG, "Notifications are unavailable") }
    }

    private var stage = "preparing"
    private var failureReason = "export_failed"
    private var delivered = 0
    private var planned = 1

    private data class PreparedBook(
        val information: BookInformation,
        val volumes: List<IndexedValue<Volume>>,
        val contents: Map<String, ChapterContent>,
        val images: Map<String, File>,
        val covers: Map<String, File>,
    )

    override suspend fun doWork(): Result {
        val book = inputData.sourceBook() ?: return bookWorkFailure("invalid_book_identity")
        val type = runCatching { ExportType.valueOf(inputData.getString("exportType").orEmpty()) }.getOrNull()
            ?: return bookWorkFailure("invalid_export_type", book)
        val selected = inputData.getString("selectedVolume").orEmpty().split(',').filter(String::isNotEmpty).toSet()
        if (type == ExportType.VOLUMES && runCatching { selected.forEach { BookIdentity.volumeRemoteId(it, book) } }.isFailure)
            return bookWorkFailure("invalid_volume_identity", book)
        val tempDir = applicationContext.cacheDir.resolve("epub/${book.fileKey}/$id")
        var complete = false
        try {
            createNotificationChannel()
            showProgressNotification()
            val foreground = if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(
                id.hashCode(), notification!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ) else ForegroundInfo(id.hashCode(), notification!!)
            setForeground(foreground)
            includeImages = inputData.getBoolean("includeImages", true)
            // WorkManager can restart this same request after process death.
            check(!tempDir.exists() || tempDir.deleteRecursively()) { "Cannot clear interrupted export" }
            val published = EpubShareFiles.files(applicationContext, id)
            if (published.isNotEmpty()) {
                // A process may die between atomic publication and WorkManager recording success.
                delivered = published.size
                planned = delivered
                complete = true
                updateCompletionNotification(book.storageKey)
                return Result.success(workDataOf("exportId" to id.toString(), "completedVolumes" to delivered, "totalVolumes" to planned))
            }
            check(tempDir.isDirectory || tempDir.mkdirs()) { "Cannot create export directory" }
            val item = MutableDownloadItem(DownloadType.EPUB_EXPORT, book.storageKey,
                bookRepository.getBookInformationFlow(book.storageKey))
            activeDownloadItem = item
            downloadProgressRepository.addExportItem(item)

            withContext(Dispatchers.IO) {
                val prepared = downloads.withBookOperation(book) { prepare(book, type, tempDir) }
                stage = "build"
                failureReason = "build_failed"
                val outputs = tempDir.resolve("outputs").apply { check(mkdirs()) }
                val groups = if (type == ExportType.BOOK) listOf(prepared.volumes) else prepared.volumes.map { listOf(it) }
                groups.forEachIndexed { index, volumes ->
                    currentCoroutineContext().ensureActive()
                    currentVolumeTitle = if (type == ExportType.BOOK) "" else volumes.single().value.volumeTitle
                    currentChapterTitle = ""
                    val title = if (type == ExportType.BOOK) prepared.information.title else currentVolumeTitle
                    val context = currentCoroutineContext()
                    val epub = EpubBuilder().apply {
                        this.title = title
                        this.id = "urn:nextvol:${book.fileKey}:${if (type == ExportType.BOOK) "book" else volumes.single().index}"
                        modifier = LocalDateTime.now(java.time.ZoneOffset.UTC)
                        creator = prepared.information.author
                        description = prepared.information.description
                        publisher = prepared.information.publishingHouse
                        cover(prepared.covers.getValue(volumes.first().value.volumeId))
                        volumes.forEach { (_, volume) ->
                            currentVolumeTitle = volume.volumeTitle
                            if (type == ExportType.BOOK) chapter {
                                title(volume.volumeTitle)
                                volume.chapters.forEach { info -> chapter { context.ensureActive(); packChapter(info, prepared, this@apply) } }
                            } else volume.chapters.forEach { info -> chapter { context.ensureActive(); packChapter(info, prepared, this@apply) } }
                        }
                    }
                    val label = if (type == ExportType.BOOK) title else "${prepared.information.title} $title"
                    val cleanTitle = label.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "Book" }
                    val safeTitle = cleanTitle.substring(0, cleanTitle.offsetByCodePoints(0, minOf(50, cleanTitle.codePointCount(0, cleanTitle.length))))
                    val prefix = if (type == ExportType.BOOK) "" else "${(index + 1).toString().padStart(groups.size.toString().length, '0')} - "
                    epub.build().save(outputs.resolve("$prefix$safeTitle.epub")) { context.ensureActive() }
                    item.progress = .7f + .15f * (index + 1) / groups.size
                }
                // Publish one directory atomically only after all selected archives are complete.
                stage = "share"
                failureReason = "share_failed"
                currentCoroutineContext().ensureActive()
                val context = currentCoroutineContext()
                EpubShareFiles.publish(applicationContext, id, outputs) { context.ensureActive() }
                currentCoroutineContext().ensureActive()
                delivered = groups.size
            }
            complete = true
            item.progress = 1f
            updateCompletionNotification(book.storageKey)
            return Result.success(workDataOf("exportId" to id.toString(), "completedVolumes" to delivered, "totalVolumes" to planned))
        } catch (cancelled: CancellationException) {
            failureReason = "cancelled"
            currentCoroutineContext().ensureActive()
            failureReason = "source_unavailable"
            return failure(book)
        } catch (problem: Exception) {
            Log.e(TAG, "Export failed for ${book.fileKey}: stage=$stage reason=$failureReason type=${problem.javaClass.simpleName}")
            return failure(book)
        } finally {
            if (!complete) {
                EpubShareFiles.directory(applicationContext, id).deleteRecursively()
                activeDownloadItem?.progress = -1f
                updateFailureNotification(book.storageKey)
            }
            tempDir.deleteRecursively()
        }
    }

    private fun failure(book: SourceBookId) = Result.failure(workDataOf(
        "reason" to failureReason, "bookId" to book.storageKey, "stage" to stage,
        "volume" to currentVolumeTitle.take(160), "chapter" to currentChapterTitle.take(160),
        "completedVolumes" to delivered, "totalVolumes" to planned,
        "message" to failureMessage(),
    ))

    private fun failureMessage(): String {
        val reason = when (failureReason) {
            "empty_volume", "empty_directory" -> R.string.epub_export_error_empty
            "invalid_content" -> R.string.epub_export_error_content
            "image_failed" -> R.string.epub_export_error_image
            "share_failed" -> R.string.epub_export_share_failed
            "cancelled" -> R.string.epub_export_error_cancelled
            "cache_failed" -> R.string.epub_export_error_cache
            else -> R.string.epub_export_notification_failed
        }
        return applicationContext.getString(R.string.epub_export_failure_detail,
            applicationContext.getString(reason),
            listOf(currentVolumeTitle, currentChapterTitle).filter(String::isNotBlank).joinToString(" / ").take(240),
            delivered, planned)
    }

    private fun <T> com.github.michaelbull.result.Result<T, WebRequestError>.value(): T = getOrElse {
        failureReason = bookWorkFailureReason(it)
        throw IOException("Source data unavailable")
    }

    private suspend fun prepare(book: SourceBookId, type: ExportType, directory: File): PreparedBook {
        val information = bookRepository.exportInformation(book).value()
        val catalog = bookRepository.exportVolumes(book).value()
        val selected = inputData.getString("selectedVolume").orEmpty().split(',').filter(String::isNotEmpty).toSet()
        val volumes = catalog.volumes.withIndex().filter { type == ExportType.BOOK || it.value.volumeId in selected }
        planned = if (type == ExportType.BOOK) 1 else volumes.size
        failureReason = "missing_volume"
        require(type != ExportType.VOLUMES || selected.isNotEmpty() && selected.size == volumes.size)
        failureReason = "empty_directory"
        require(volumes.isNotEmpty())
        volumes.forEach { (_, volume) ->
            currentVolumeTitle = volume.volumeTitle
            failureReason = "empty_volume"
            require(volume.chapters.isNotEmpty())
        }
        val allChapters = catalog.volumes.flatMap { it.chapters }.distinctBy { it.id }
        val positions = allChapters.mapIndexed { index, chapter -> chapter.id to index }.toMap()
        failureReason = "cache_failed"
        val savedRevision = downloads.revision(book)
        val revision = bookRepository.sourceRevision(book).ifEmpty { savedRevision.orEmpty() }
        val refreshImages = savedRevision != null && savedRevision != revision
        val local = LocalBookStore.isLocal(book)
        val attempt = if (local) null else downloads.begin(book, inputData.getLong("downloadGeneration", 0), id.toString())
        var cached = false
        try {
            attempt?.let { downloads.target(it, catalog, revision, information.coverUri.toString()) }
            val raw = linkedMapOf<String, ChapterContent>()
            val rendered = linkedMapOf<String, ChapterContent>()
            val chapterImages = mutableMapOf<String, List<String>>()
            val images = linkedMapOf<String, File>()
            val tasks = linkedMapOf<Pair<String, Boolean>, ImageDownloader.Task>()
            val locations = mutableMapOf<File, Pair<String, String>>()
            fun imageTask(uri: Uri, cover: Boolean = false, optional: Boolean = false): File {
                val key = uri.toString() to cover
                return tasks.getOrPut(key) {
                    val hash = downloadHash("$cover:${uri}")
                    val file = directory.resolve("image_$hash.jpg")
                    locations[file] = currentVolumeTitle to currentChapterTitle
                    ImageDownloader.Task(file, uri, cover,
                        if (optional) DefaultBookCoverRenderer.Text(book.storageKey, information.title, information.author) else null,
                        fresh = refreshImages)
                }.file
            }
            stage = "chapters"
            totalChapters = volumes.sumOf { it.value.chapters.size }
            processedChapters = 0
            for ((_, volume) in volumes) {
                currentVolumeTitle = volume.volumeTitle
                for (chapter in volume.chapters) {
                    currentCoroutineContext().ensureActive()
                    currentChapterTitle = chapter.title
                    val signature = downloadChapterSignature(allChapters, positions.getValue(chapter.id), revision)
                    val content = attempt?.let { downloads.reusable(it, chapter.id, signature) }
                        ?: if (attempt != null && downloads.hasVersionedChapter(attempt, chapter.id))
                            bookRepository.downloadChapter(book, chapter.id).value()
                        else bookRepository.exportChapter(book, chapter.id).value()
                    failureReason = "invalid_content"
                    require(content.id == chapter.id) { "Chapter identity mismatch" }
                    val references = linkedSetOf<String>()
                    contentJsonDecoder.decodeForExport(content.content) { data ->
                        visitImages(data.toHtmlElement(applicationContext)) { uri -> references += uri.toString() }
                    }
                    raw[chapter.id] = content
                    chapterImages[chapter.id] = references.toList()
                    val processed = bookRepository.exportContent(book, content)
                    // Validate the processed snapshot too; conversion must not silently omit components.
                    contentJsonDecoder.decodeForExport(processed.content) { data ->
                        if (includeImages) visitImages(data.toHtmlElement(applicationContext)) { uri ->
                            images[uri.toString()] = imageTask(uri)
                        }
                    }
                    if (includeImages) references.forEach { uri -> images[uri] = imageTask(Uri.parse(uri)) }
                    rendered[chapter.id] = processed
                    processedChapters++
                    val progress = (processedChapters * 40 / totalChapters)
                    activeDownloadItem?.progress = progress / 100f
                    buildProgressNotification(progress, applicationContext.getString(
                        R.string.epub_export_notification_stage_chapters, processedChapters, totalChapters))
                }
            }
            currentVolumeTitle = ""
            currentChapterTitle = ""
            val cover = if (information.coverUri.toString().isBlank()) directory.resolve("cover.jpg").also {
                DefaultBookCoverRenderer.writeTo(applicationContext, it, information.title, book.storageKey, information.author)
            } else imageTask(information.coverUri, cover = true, optional = true)
            suspend fun acquire(pending: List<ImageDownloader.Task>) {
                stage = "images"
                failureReason = "image_failed"
                val tasks = pending.map { task -> task.copy(fresh = task.fresh ||
                    (attempt?.let { downloads.isImageStale(it, task.uri.toString(), task.cover) } == true)) }
                val result = ImageDownloader(applicationContext, book, tasks,
                    onTask = { task ->
                        val location = locations[task.file]
                        currentVolumeTitle = location?.first.orEmpty()
                        currentChapterTitle = location?.second.orEmpty()
                    },
                    onDownloaded = { task ->
                        attempt?.let {
                            if (task.fresh || !downloads.hasImage(it, task.uri.toString(), task.cover))
                                downloads.retainImage(it, SourceImage(book, task.uri.toString(), task.cover), null)
                        }
                    },
                    onProgress = { count, total ->
                        val progress = 40 + (30f * count / total).toInt()
                        activeDownloadItem?.progress = progress / 100f
                        buildProgressNotification(progress, applicationContext.getString(
                            R.string.epub_export_notification_stage_images, count, total))
                    }).run()
                check(result == Result.success()) { "Image preparation failed" }
            }
            acquire(tasks.values.toList())
            val covers = linkedMapOf<String, File>()
            for ((index, volume) in volumes) {
                currentCoroutineContext().ensureActive()
                currentVolumeTitle = volume.volumeTitle
                currentChapterTitle = ""
                val uri = if (type == ExportType.VOLUMES && index != 0 && includeImages) {
                    // Volume artwork is optional. Prepared body images can also be reused as covers.
                    try { bookRepository.volumeCover(book, volume, raw, applicationContext).get() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } else null
                val previous = tasks.size
                covers[volume.volumeId] = uri?.let { images[it.toString()] ?: imageTask(it, optional = true) } ?: cover
                if (tasks.size > previous) acquire(tasks.values.drop(previous))
            }
            stage = "cache"
            failureReason = "cache_failed"
            attempt?.let { active ->
                for ((chapterId, content) in raw) {
                    currentCoroutineContext().ensureActive()
                    downloads.saveChapter(active, content,
                        downloadChapterSignature(allChapters, positions.getValue(chapterId), revision),
                        chapterImages.getValue(chapterId), requireImages = includeImages)
                }
                check(bookRepository.sourceRevision(book).let { it.isEmpty() || it == revision }) { "Source changed during preparation" }
                downloads.finish(active, success = true)
            }
            cached = true
            val processedCatalog = bookRepository.exportCatalog(catalog)
            return PreparedBook(bookRepository.exportMetadata(information),
                volumes.map { IndexedValue(it.index, processedCatalog.volumes[it.index]) }, rendered, images, covers)
        } finally {
            if (!cached && attempt != null) withContext(NonCancellable) {
                try { downloads.finish(attempt, success = false) }
                catch (_: CancellationException) { /* Cleared/replaced: never recreate its download ownership. */ }
                catch (problem: Exception) { Log.e(TAG, "Could not save download state: ${problem.javaClass.simpleName}") }
            }
        }
    }

    private fun visitImages(element: Element, action: (Uri) -> Unit) {
        element.attribute("src")?.let { src ->
            require(src.value.isNotBlank()) { "Empty image resource" }
            action(Uri.parse(src.value))
        }
        element.elements().forEach { visitImages(it, action) }
    }

    private fun ChapterBuilder.packChapter(info: ChapterInformation, prepared: PreparedBook, epub: EpubBuilder) {
        currentChapterTitle = info.title
        title(info.title)
        content {
            title(info.title)
            contentJsonDecoder.decodeForExport(prepared.contents.getValue(info.id).content) { data ->
                fun localize(element: Element): Element? {
                    if (!includeImages && (element.name in setOf("img", "image") || element.attribute("src") != null)) return null
                    element.attribute("src")?.let { src ->
                        val file = prepared.images.getValue(src.value)
                        src.value = "image/${file.name}"
                        epub.imgRes(src.value, file.nameWithoutExtension, file)
                    }
                    element.elements().toList().forEach { if (localize(it) == null) it.detach() }
                    return element
                }
                localize(data.toHtmlElement(applicationContext))?.let(::addContent)
            }
        }
    }

}
