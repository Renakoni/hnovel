package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentJsonDecoder
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.download.MutableDownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import com.github.michaelbull.result.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.ExportType
import indi.dmzz_yyhyy.lightnovelreader.utils.DefaultBookCoverRenderer
import indi.dmzz_yyhyy.lightnovelreader.utils.network.ImageDownloader
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.potatoepub.builder.ChapterBuilder
import io.nightfish.potatoepub.builder.EpubBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import org.dom4j.Element
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime

@HiltWorker
class ExportBookToEPUBWork @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bookRepository: BookRepository,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val contentJsonDecoder: ContentJsonDecoder
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

    private fun showProgressNotification(bookId: String) {
        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(applicationContext.getString(R.string.epub_export_notification_preparing))
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(ofId(bookId), 0, notification)
    }

    private fun updateFailureNotification(bookId: String) {

        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(applicationContext.getString(R.string.epub_export_notification_failed))
            .setSmallIcon(R.drawable.file_export_24px)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(ofId(bookId), 0, notification)
    }

    private fun updateCompletionNotification(bookId: String) {
        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(applicationContext.getString(R.string.epub_export_notification_success))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(ofId(bookId), 0, notification)
    }

    private fun buildProgressNotification(
        bookId: String,
        progress: Int,
        stage: String
    ) {
        val text = "$stage $currentVolumeTitle / $currentChapterTitle"

        if (notification == null) {
            notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
                .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
                .setSmallIcon(R.drawable.file_export_24px)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: "") + " ($progress%)")
            .setContentText(text)
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(100, progress, false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(ofId(bookId), 0, notification)
    }

    override suspend fun doWork(): Result {
        val book = inputData.sourceBook() ?: return bookWorkFailure("invalid_book_identity")
        return try {
            val result = exportBook(book)
            if (result is Result.Failure) {
                activeDownloadItem?.progress = -1f
                updateFailureNotification(book.storageKey)
                if (result.outputData.getString("reason") == null) bookWorkFailure("export_failed", book) else result
            } else {
                activeDownloadItem?.progress = 1f
                updateCompletionNotification(book.storageKey)
                result
            }
        } catch (failure: CancellationException) {
            activeDownloadItem?.progress = -1f
            updateFailureNotification(book.storageKey)
            currentCoroutineContext().ensureActive()
            bookWorkFailure("source_unavailable", book)
        } catch (failure: Exception) {
            activeDownloadItem?.progress = -1f
            updateFailureNotification(book.storageKey)
            bookWorkFailure("export_failed", book)
        } finally {
            applicationContext.cacheDir.resolve("epub").resolve(book.fileKey).resolve(id.toString()).deleteRecursively()
        }
    }

    private suspend fun exportBook(book: SourceBookId): Result = withContext(Dispatchers.IO) {
        createNotificationChannel()
        val bookId = book.storageKey
        showProgressNotification(bookId)
        val exportType = runCatching { ExportType.valueOf(inputData.getString("exportType") ?: "") }.getOrNull()
            ?: return@withContext bookWorkFailure("invalid_export_type", book)
        includeImages = inputData.getBoolean("includeImages", true)
        val selectedVolumeRaw = inputData.getString("selectedVolume")
        val selectedVolumes = selectedVolumeRaw?.split(",")?.filter(String::isNotEmpty)
        if (exportType == ExportType.VOLUMES && (selectedVolumes.isNullOrEmpty() ||
            selectedVolumes.any { runCatching { BookIdentity.volumeRemoteId(it, book) }.isFailure })) {
            return@withContext bookWorkFailure("invalid_volume_identity", book)
        }
        Log.d(TAG, "start export target=${book.fileKey} type=$exportType includeImages=$includeImages")
        val fileUri = inputData.getString("uri")?.let(Uri::parse) ?: return@withContext Result.failure()
        val tempDir = applicationContext.cacheDir.resolve("epub")
            .resolve(book.fileKey).resolve(id.toString()).apply { mkdirs() }
        val cover = tempDir.resolve("cover.jpg")
            .also {
                if (it.exists()) it.delete()
            }
        val downloadItem = MutableDownloadItem(
            DownloadType.EPUB_EXPORT,
            bookId,
            bookRepository.getBookInformationFlow(bookId)
        )
        activeDownloadItem = downloadItem
        downloadProgressRepository.addExportItem(downloadItem)
        val tasks = mutableListOf<ImageDownloader.Task>()

        bookRepository.getBookInformationFlow(bookId).last()
            .andThen { bookInformation ->
                bookRepository.getBookVolumesFlow(bookId).last()
                    .andThen { bookVolumes ->
                        val bookContentMap = mutableMapOf<String, ChapterContent>()
                        downloadItem.progress = 0f
                        val volumesToProcess = when (exportType) {
                            ExportType.BOOK -> bookVolumes.volumes
                            ExportType.VOLUMES -> {
                                if (selectedVolumes.isNullOrEmpty()) {
                                    updateFailureNotification(bookId)
                                    downloadItem.progress = -1f
                                    return@withContext Result.failure()
                                }
                                bookVolumes.volumes.filter { selectedVolumes.contains(it.volumeId) }.also { volumes ->
                                    if (volumes.size != selectedVolumes.distinct().size) return@withContext bookWorkFailure("missing_volume", book)
                                }
                            }
                        }

                        totalChapters = volumesToProcess.sumOf { it.chapters.size }
                        processedChapters = 0
                        coroutineBinding {
                            volumesToProcess.forEach { volume ->
                                currentVolumeTitle = volume.volumeTitle

                                volume.chapters.forEach {
                                    currentChapterTitle = it.title
                                    Log.d(TAG, "load chapter ${processedChapters + 1}/$totalChapters")

                                    bookContentMap[it.id] = bookRepository.getChapterContentFlow(
                                        chapterId = it.id,
                                        bookId = bookId,
                                    ).last().bind()

                                    processedChapters++
                                    val progress = (processedChapters.toFloat() / totalChapters * 50).toInt()
                                    buildProgressNotification(
                                        bookId,
                                        progress,
                                        applicationContext.getString(
                                            R.string.epub_export_notification_stage_chapters,
                                            processedChapters,
                                            totalChapters
                                        )
                                    )
                                    downloadItem.progress = progress / 100f
                                }
                            }
                        }.onOk {
                            return@withContext when (exportType) {
                                ExportType.BOOK -> bookToEPUB(
                                    bookInformation,
                                    bookVolumes,
                                    bookContentMap,
                                    tempDir,
                                    tasks,
                                    bookVolumes.volumes.size,
                                    bookId,
                                    downloadItem,
                                    cover,
                                    fileUri
                                )
                                ExportType.VOLUMES -> volumesToEPUB(
                                    selectedVolumes!!,
                                    bookInformation,
                                    bookVolumes,
                                    bookContentMap,
                                    tempDir,
                                    tasks,
                                    bookVolumes.volumes.size,
                                    bookId,
                                    downloadItem,
                                    cover,
                                    fileUri
                                )
                            }
                        }
                    }
            }.onErr {
                downloadItem.progress = -1f
                updateFailureNotification(bookId)
                return@withContext bookWorkFailure(bookWorkFailureReason(it), book)
            }

        return@withContext Result.success()
    }

    private suspend fun volumesToEPUB(
        selectedVolume: List<String>,
        bookInformation: BookInformation,
        bookVolumes: BookVolumes,
        bookContentMap: MutableMap<String, ChapterContent>,
        tempDir: File,
        tasks: MutableList<ImageDownloader.Task>,
        volumesCount: Int,
        bookId: String,
        downloadItem: MutableDownloadItem,
        cover: File,
        fileUri: Uri
    ): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "export ${selectedVolume.size} volumes")
        val epubs = mutableListOf<Pair<String, EpubBuilder>>()
        if (bookInformation.coverUri.toString().isBlank()) {
            DefaultBookCoverRenderer.writeTo(
                applicationContext,
                cover,
                bookInformation.title, bookInformation.id, bookInformation.author
            )
        } else {
            tasks.add(ImageDownloader.Task(cover, bookInformation.coverUri, cover = true,
                defaultCover = DefaultBookCoverRenderer.Text(bookInformation.id, bookInformation.title, bookInformation.author)))
        }
        for ((currentVolumeIndex, volume) in bookVolumes.volumes.withIndex()) {
            if (!selectedVolume.contains(volume.volumeId)) continue

            Log.d(TAG, "build volume=${volume.volumeTitle}")
            val epub = EpubBuilder().apply {
                title = volume.volumeTitle
                modifier = LocalDateTime.now()
                creator = bookInformation.author
                description = bookInformation.description
                publisher = bookInformation.publishingHouse
                if (currentVolumeIndex == 0) cover(cover)
                else {
                    val url = try {
                        bookRepository.volumeCover(
                            BookIdentity.book(bookId),
                            volume,
                            bookContentMap,
                            applicationContext
                        ).onErr { return@withContext bookWorkFailure(bookWorkFailureReason(it), BookIdentity.book(bookId)) }.get()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        null
                    }
                    if (url == null) {
                        cover(cover)
                    } else {
                        val image = tempDir.resolve(url.hashCode().toString() + ".jpg")
                        tasks.add(ImageDownloader.Task(image, url,
                            defaultCover = DefaultBookCoverRenderer.Text(bookInformation.id, bookInformation.title, bookInformation.author)))
                        cover(image)
                    }
                }
                val progressForVolume = (30 * currentVolumeIndex) / volumesCount
                downloadItem.progress = (20f + progressForVolume) / 100f
                for (chapterInformation in volume.chapters) {
                    Log.d(TAG, "pack chapter=${chapterInformation.title}")
                    chapter {
                        packChapter(chapterInformation, bookContentMap, tempDir, tasks, this@apply)
                    }
                }
            }
            // Titles can repeat across sources and within a book. Keep every selected volume.
            epubs += "${bookInformation.title} ${volume.volumeTitle} [${BookIdentity.book(bookId).fileKey}-$currentVolumeIndex].epub" to epub
        }

        Log.d(TAG, "image tasks size=${tasks.size}")
        val imageDownloader = ImageDownloader(
            context = applicationContext,
            book = BookIdentity.book(bookId),
            tasks = tasks,
            onProgress = { current, total ->
                val progress = 50 + (current.toFloat() / total * 40).toInt()
                Log.d(TAG, "image download progress=$current/$total")

                buildProgressNotification(
                    bookId,
                    progress,
                    applicationContext.getString(
                        R.string.epub_export_notification_stage_images,
                        current,
                        total
                    )
                )

                downloadItem.progress = progress / 100f
            }
        )

        if (async { imageDownloader.run() }.await() == Result.failure()) {
            downloadItem.progress = -1f
            return@withContext Result.failure()
        }

        val folder = DocumentFile.fromTreeUri(applicationContext, fileUri)
        if (folder == null) {
            downloadItem.progress = -1f
            return@withContext Result.failure()
        }
        for ((fileName, epub) in epubs) {
            val epubUri = folder.createFile("application/epub+zip", fileName)?.uri
            if (epubUri == null) {
                downloadItem.progress = -1f
                return@withContext Result.failure()
            }
            val result = saveEpub(
                bookId,
                downloadItem,
                tempDir,
                epub,
                epubUri
            )
            if (result == Result.failure()) {
                downloadItem.progress = -1f
                return@withContext Result.failure()
            }
        }

        return@withContext Result.success()
    }

    private suspend fun bookToEPUB(
        bookInformation: BookInformation,
        bookVolumes: BookVolumes,
        bookContentMap: MutableMap<String, ChapterContent>,
        tempDir: File,
        tasks: MutableList<ImageDownloader.Task>,
        volumesCount: Int,
        bookId: String,
        downloadItem: MutableDownloadItem,
        cover: File,
        fileUri: Uri
    ): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "export full book")

        var currentVolumeIndex = 0
        val epub = EpubBuilder().apply {
            title = bookInformation.title
            modifier = LocalDateTime.now()
            creator = bookInformation.author
            description = bookInformation.description
            publisher = bookInformation.publishingHouse

            bookVolumes.volumes.forEach { volume ->
                Log.d(TAG, "pack volume=${volume.volumeTitle}")
                chapter {
                    packVolume(volume, bookContentMap, tempDir, tasks, this@apply)
                }
                currentVolumeIndex++
                val progressForVolume = (30 * currentVolumeIndex) / volumesCount
                downloadItem.progress = (20f + progressForVolume) / 100f
            }
            if (bookInformation.coverUri.toString().isBlank()) {
                DefaultBookCoverRenderer.writeTo(
                    applicationContext,
                    cover,
                    bookInformation.title, bookInformation.id, bookInformation.author
                )
            } else {
                tasks.add(ImageDownloader.Task(cover, bookInformation.coverUri, cover = true,
                defaultCover = DefaultBookCoverRenderer.Text(bookInformation.id, bookInformation.title, bookInformation.author)))
            }
            cover(cover)
        }

        Log.d(TAG, "image tasks size=${tasks.size}")
        val imageDownloader = async {
            ImageDownloader(
                context = applicationContext,
                book = BookIdentity.book(bookId),
                tasks = tasks,
                onProgress = { current, total ->
                    val progress = (50 + current.toFloat() / total * 40).toInt()
                    buildProgressNotification(
                        bookId,
                        progress,
                        applicationContext.getString(
                            R.string.epub_export_notification_stage_images,
                            current,
                            total
                        )
                    )
                    downloadItem.progress = progress / 100f
                }
            ).run()
        }

        if (imageDownloader.await() == Result.success()) {
            saveEpub(bookId, downloadItem, tempDir, epub, fileUri).also {
                if (it == Result.success()) {
                    Log.d(TAG, "export success")
                    updateCompletionNotification(bookId)
                } else {
                    downloadItem.progress = -1f
                    updateFailureNotification(bookId)
                }
            }
        } else {
            downloadItem.progress = -1f
            updateFailureNotification(bookId)
            Result.failure()
        }
    }

    private fun ChapterBuilder.packVolume(
        volume: Volume,
        bookContentMap: MutableMap<String, ChapterContent>,
        tempDir: File,
        tasks: MutableList<ImageDownloader.Task>,
        epubBuilder: EpubBuilder
    ) {
        title(volume.volumeTitle)
        volume.chapters.forEach {
            chapter {
                packChapter(it, bookContentMap, tempDir, tasks, epubBuilder)
            }
        }
    }

    private fun ChapterBuilder.packChapter(
        it: ChapterInformation,
        bookContentMap: MutableMap<String, ChapterContent>,
        tempDir: File,
        tasks: MutableList<ImageDownloader.Task>,
        epubBuilder: EpubBuilder
    ) {
        Log.d(TAG, "render chapter=${it.title}")
        title(it.title)
        content {
            title(it.title)
            contentJsonDecoder.getDataFromJsonObject(bookContentMap[it.id]!!.content) {
                addContent(
                    it.toHtmlElement(applicationContext).also { element ->
                        element.parseSrc(tempDir, tasks, epubBuilder, includeImages)
                    }
                )
            }
        }
    }

    private fun Element.parseSrc(
        tempDir: File,
        tasks: MutableList<ImageDownloader.Task>,
        epubBuilder: EpubBuilder,
        includeImages: Boolean
    ) {
        val src = this.attributes().firstOrNull { it.name == "src" }
        if (src != null && src.value.runCatching { this.toUri() }.isSuccess) {
            val id = java.security.MessageDigest.getInstance("SHA-256")
                .digest(src.value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val image = tempDir.resolve("image_$id.jpg")
            tasks.add(ImageDownloader.Task(image, src.value.toUri()))
            src.value = "image/image_$id.jpg"
            epubBuilder.imgRes(
                href = src.value,
                id = "image_$id",
                file = image
            )
        }
        this.elements().forEach {
            it.parseSrc(tempDir, tasks, epubBuilder, includeImages)
        }
    }

    private fun saveEpub(
        bookId: String,
        downloadItem: MutableDownloadItem,
        tempDir: File,
        epub: EpubBuilder,
        fileUri: Uri
    ): Result {
        Log.d(TAG, applicationContext.getString(R.string.epub_export_notification_stage_save))
        downloadItem.progress = 0.90f

        val file = tempDir.resolve("epub")
        try {
            epub.build().save(file)
        } catch (e: Exception) {
            Log.d(TAG, "EPUB build failed: ${e.javaClass.simpleName}")
            updateFailureNotification(bookId)
            downloadItem.progress = -1f
            return Result.failure()
        }
        downloadItem.progress = 0.95f
        requireNotNull(applicationContext.contentResolver.openOutputStream(fileUri)) { "Cannot open EPUB destination" }
            .use { outputStream ->
                FileInputStream(file).use { inputStream ->
                    val buffer = ByteArray(1024 * 1024) // = 1MB
                    var bytesRead: Int
                    var totalBytes = 0L
                    val fileSize = file.length()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (fileSize > 0) {
                            val writeProgress = 90 + (totalBytes.toFloat() / fileSize * 10).toInt()
                            buildProgressNotification(bookId, writeProgress, "${totalBytes / 1024}/${fileSize / 1024} KB")
                            downloadItem.progress = writeProgress / 100f
                        }
                    }
                }
            }
        Log.d(TAG, "save finished")
        return Result.success()
    }
}
