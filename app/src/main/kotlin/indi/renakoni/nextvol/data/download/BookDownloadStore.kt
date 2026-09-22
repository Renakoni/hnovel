package indi.renakoni.nextvol.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.AtomicFile
import androidx.room.withTransaction
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.sourceImageCacheKey
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.local.room.entity.DownloadedChapterEntity
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Download ownership and file commits. Network requests stay outside this store's lock. */
@Singleton
class BookDownloadStore @Inject constructor(@ApplicationContext private val context: Context,
    private val database: NextVolDatabase, private val decoder: ContentJsonDecoder) {
    data class Attempt(val book: SourceBookId, val generation: Long, val id: String)

    private val dao = database.bookDownloadDao()
    private val lock = Mutex()
    private val bookOperations = ConcurrentHashMap<String, Mutex>()
    private val root = File(context.filesDir, "book-downloads")
    private val imageKeys = context.getSharedPreferences("source_image_cache_keys", Context.MODE_PRIVATE)

    companion object {
        private const val GROUP = "hnovel/downloads"
        private const val EPOCH = "$GROUP/generation"
        private const val MIGRATED = "$GROUP/legacy-imported"
    }

    fun generation(): Long = database.userDataDao().getEntity(EPOCH)?.value?.toLong() ?: 0
    fun observe(book: SourceBookId) = combine(dao.observe(book.storageKey), dao.observeChapters(book.storageKey)) { _, _ -> Unit }

    suspend fun prepare() = withContext(Dispatchers.IO) { lock.withLock { removeRetiredGenerations(); migrateLegacy() } }
    /** Normal downloads and export preparation must not replace each other's attempt. */
    suspend fun <T> withBookOperation(book: SourceBookId, block: suspend () -> T): T =
        bookOperations.getOrPut(book.storageKey) { Mutex() }.withLock { block() }
    suspend fun entries() = withContext(Dispatchers.IO) { lock.withLock { migrateLegacy(); dao.getAll() } }

    suspend fun revision(book: SourceBookId): String? = withContext(Dispatchers.IO) {
        lock.withLock { dao.get(book.storageKey)?.revision }
    }

    internal fun chapterImages(chapter: ChapterContent): List<String> = buildList {
        decoder.getDataFromJsonObject(chapter.content) { if (it is ImageComponentData) add(it.uri.toString()) }
    }.distinct()

    private fun imageFile(book: SourceBookId, generation: Long, uri: String, cover: Boolean): File =
        File(root, "$generation/${book.fileKey}/${downloadHash(BookIdentity.encode("download-image", listOf(cover.toString(), uri)))}")

    suspend fun image(image: SourceImage): File? = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        val owner = dao.get(image.book.storageKey) ?: return@withLock null
        imageFile(image.book, owner.generation, image.uri, image.cover).takeIf { it.isFile }
    } }

    suspend fun begin(book: SourceBookId, generation: Long, id: String): Attempt = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        currentCoroutineContext().ensureActive()
        if (generation != this@BookDownloadStore.generation()) throw CancellationException("Download was cleared")
        val previous = dao.get(book.storageKey) ?: BookDownloadEntity(book.storageKey, generation = generation)
        dao.put(previous.copy(phase = "updating", generation = generation, attempt = id))
        Attempt(book, generation, id)
    } }

    private suspend fun <T> current(attempt: Attempt, block: suspend (BookDownloadEntity) -> T): T =
        withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive()
            val owner = dao.get(attempt.book.storageKey)
            if (attempt.generation != generation() || owner?.attempt != attempt.id)
                throw CancellationException("Download was cleared or replaced")
            block(checkNotNull(owner))
        } }

    suspend fun target(attempt: Attempt, volumes: BookVolumes, revision: String, coverUri: String) = current(attempt) {
        require(volumes.bookId == attempt.book.storageKey)
        val hash = downloadDirectoryHash(volumes)
        val unchanged = it.directoryHash == hash && it.revision == revision && it.coverUri == coverUri
        if (it.revision != revision) {
            // Keep old bytes readable, but a failed/text-only update must not label them current.
            val directory = imageFile(attempt.book, attempt.generation, "", false).parentFile!!
            directory.listFiles()?.filter { file -> file.isFile && file.name.matches(Regex("[0-9a-f]{64}")) }
                ?.forEach { file -> check(staleMarker(file).isFile || staleMarker(file).createNewFile()) }
        }
        dao.put(it.copy(directoryHash = hash, revision = revision, coverUri = coverUri))
        unchanged
    }

    suspend fun reusable(attempt: Attempt, chapterId: String, signature: String): ChapterContent? = current(attempt) {
        val saved = dao.chapter(chapterId)?.takeIf { it.bookId == attempt.book.storageKey && it.signature == signature }
            ?: return@current null
        database.chapterContentDao().get(saved.id)?.let { body ->
            ChapterContent(body.id, body.title, body.content, body.prevChapter.ifEmpty { null }, body.nextChapter.ifEmpty { null })
        }
    }

    suspend fun hasVersionedChapter(attempt: Attempt, chapterId: String): Boolean = current(attempt) {
        // Unversioned pre-upgrade downloads remain usable offline, like ordinary reading cache.
        !dao.chapter(chapterId)?.signature.isNullOrEmpty()
    }

    suspend fun hasImage(attempt: Attempt, uri: String, cover: Boolean = false): Boolean = current(attempt) {
        val file = imageFile(attempt.book, attempt.generation, uri, cover)
        file.isFile && !staleMarker(file).exists()
    }

    private fun staleMarker(file: File) = File(file.parentFile, "${file.name}.stale")

    suspend fun isImageStale(attempt: Attempt, uri: String, cover: Boolean): Boolean = current(attempt) {
        staleMarker(imageFile(attempt.book, attempt.generation, uri, cover)).exists()
    }

    suspend fun saveImage(attempt: Attempt, uri: String, cover: Boolean, bytes: ByteArray) = current(attempt) {
        writeImage(imageFile(attempt.book, attempt.generation, uri, cover), bytes)
    }

    /** Copy the successfully decoded source bytes, not a resized/re-encoded bitmap. */
    suspend fun retainImage(attempt: Attempt, image: SourceImage, cacheKey: String?) = current(attempt) {
        require(image.book == attempt.book)
        val bytes = cachedImageBytes(image, cacheKey) ?: error("Downloaded image bytes are unavailable")
        writeImage(imageFile(image.book, attempt.generation, image.uri, image.cover), bytes)
    }

    private fun writeImage(target: File, bytes: ByteArray) {
        check(bytes.isNotEmpty()) { "Downloaded image is empty" }
        val previousTime = target.lastModified()
        val file = AtomicFile(target)
        val output = file.startWrite()
        try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        check(file.openRead().use { it.readBytes().contentEquals(bytes) }) { "Download image was not committed" }
        // File mtime participates in Coil's key; two same-sized updates in one tick must differ.
        if (target.lastModified() <= previousTime)
            check(target.setLastModified(previousTime + 1)) { "Could not update downloaded image version" }
        val stale = staleMarker(target)
        check(!stale.exists() || stale.delete()) { "Could not commit downloaded image version" }
    }

    suspend fun saveChapter(attempt: Attempt, chapter: ChapterContent, signature: String, images: List<String>,
        requireImages: Boolean = true) = current(attempt) {
        require(SourceChapterId.fromStorageKey(chapter.id).book == attempt.book)
        listOfNotNull(chapter.prevChapter, chapter.nextChapter).forEach {
            require(SourceChapterId.fromStorageKey(it).book == attempt.book)
        }
        if (requireImages) check(images.all { uri ->
            val file = imageFile(attempt.book, attempt.generation, uri, false)
            file.isFile && !staleMarker(file).exists()
        })
        database.withTransaction {
            database.chapterContentDao().update(chapter)
            dao.put(DownloadedChapterEntity(chapter.id, attempt.book.storageKey, signature, Json.encodeToString(images)))
        }
    }

    suspend fun finish(attempt: Attempt, success: Boolean) = current(attempt) {
        dao.put(it.copy(phase = if (success) "complete" else "failed", attempt = ""))
    }

    suspend fun state(book: SourceBookId, volumes: BookVolumes?, revision: String?, active: Boolean): BookDownloadState =
        withContext(Dispatchers.IO) { lock.withLock {
            migrateLegacy()
            val owner = dao.get(book.storageKey) ?: return@withLock BookDownloadState(
                if (active) BookDownloadPhase.Updating else BookDownloadPhase.None)
            val chapters = volumes?.volumes.orEmpty().flatMap { it.chapters }.distinctBy { it.id }
            val records = dao.chapters(book.storageKey).associateBy { it.id }
            if (active) return@withLock BookDownloadState(BookDownloadPhase.Updating,
                chapters.count { it.id in records }, chapters.size)
            val savedIds = dao.savedContentIds(book.storageKey).toSet()
            val count = chapters.count { chapter ->
                val saved = records[chapter.id]
                saved != null && chapter.id in savedIds &&
                    Json.decodeFromString<List<String>>(saved.images).all {
                        val file = imageFile(book, owner.generation, it, false)
                        file.isFile && !staleMarker(file).exists()
                    }
            }
            val coverFile = imageFile(book, owner.generation, owner.coverUri, true)
            val coverSaved = owner.coverUri.isEmpty() || coverFile.isFile && !staleMarker(coverFile).exists()
            val signaturesCurrent = chapters.withIndex().all { (index, chapter) ->
                val signature = records[chapter.id]?.signature
                signature == downloadChapterSignature(chapters, index, owner.revision) ||
                    signature == "" && owner.revision.isEmpty()
            }
            val phase = when {
                owner.phase == "failed" -> BookDownloadPhase.Failed
                owner.phase == "updating" -> BookDownloadPhase.Partial // interrupted process
                volumes != null && owner.directoryHash != downloadDirectoryHash(volumes) -> BookDownloadPhase.Outdated
                !revision.isNullOrEmpty() && owner.revision.isNotEmpty() && owner.revision != revision -> BookDownloadPhase.Outdated
                chapters.isNotEmpty() && count == chapters.size && coverSaved && signaturesCurrent -> BookDownloadPhase.Complete
                else -> BookDownloadPhase.Partial
            }
            BookDownloadState(phase, count, chapters.size)
        } }

    suspend fun clearReadingCache() = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        dao.clearReadingContent()
        SingletonImageLoader.get(context).apply { memoryCache?.clear(); diskCache?.clear() }
    } }

    /** Return the retired generation so WorkManager cancels only requests submitted before this clear. */
    suspend fun clearDownloads(): Long = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        val retired = generation()
        val owners = dao.getAll()
        val images = imagesFor(owners)
        database.withTransaction {
            database.userDataDao().insert(EPOCH, GROUP, "Long", Math.addExact(retired, 1).toString())
            dao.clearDownloadedContent(); dao.clearChapters(); dao.clearBooks()
            clearHistory(null)
        }
        clearCachedImages(images)
        check(!root.exists() || root.deleteRecursively()) { "Could not remove downloaded images" }
        retired
    } }

    /** Explicit per-book removal; the caller cancels that book's unique WorkManager request first. */
    suspend fun removeBooks(books: List<SourceBookId>) = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        val keys = books.map { it.storageKey }
        val owners = keys.mapNotNull { dao.get(it) }
        val images = imagesFor(owners)
        database.withTransaction {
            dao.deleteContent(keys); dao.deleteChapters(keys); dao.deleteBooks(keys)
            clearHistory(keys.toSet())
        }
        clearCachedImages(images)
        for (book in books) {
            val generation = owners.find { it.bookId == book.storageKey }?.generation ?: generation()
            val directory = File(root, "$generation/${book.fileKey}")
            check(!directory.exists() || directory.deleteRecursively()) { "Could not remove downloaded images" }
        }
    } }

    private suspend fun imagesFor(owners: List<BookDownloadEntity>): List<SourceImage> = owners.flatMap { owner ->
        val book = SourceBookId.fromStorageKey(owner.bookId)
        dao.chapters(owner.bookId).flatMap { Json.decodeFromString<List<String>>(it.images) }
            .map { SourceImage(book, it) } +
            listOfNotNull(owner.coverUri.takeIf(String::isNotEmpty)?.let { SourceImage(book, it, true) })
    }

    private suspend fun clearHistory(bookIds: Set<String>?) {
        database.userDataDao().getEntity(UserDataPath.CompletedDownloadBookList.path)?.let { history ->
            database.userDataDao().insert(history.copy(value = history.value.split(',').filterNot { entry ->
                val fields = entry.trim().split('|')
                fields.size == 2 && fields[0] == DownloadType.CACHE.name && (bookIds == null || fields[1] in bookIds)
            }.joinToString(",")))
        }
    }

    private fun clearCachedImages(images: List<SourceImage>) {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        images.forEach { image ->
            imageKeys.getString(sourceImageCacheKey(image, ""), null)?.let { loader.diskCache?.remove(it) }
        }
    }

    /**
     * The caller holds the statistics lock. The library and download ownership commit together.
     * Overwrite uses a new file generation: rollback/process death can never erase the old files.
     * Backups contain bodies/ownership, not running jobs or image files; missing images stay partial.
     */
    suspend fun restore(
        books: List<BookDownloadEntity>, chapters: List<DownloadedChapterEntity>, legacy: Boolean,
        overwrite: Boolean = false, beforeCommit: () -> Unit = {}, writeLibrary: suspend () -> Unit = {},
    ) = withContext(Dispatchers.IO) { lock.withLock {
        val images = if (overwrite) imagesFor(dao.getAll()) else emptyList()
        removeRetiredGenerations()
        database.withTransaction {
            if (overwrite) {
                val next = Math.addExact(generation(), 1)
                check(!File(root, next.toString()).exists()) { "Download restore directory is unavailable" }
                database.userDataDao().insert(EPOCH, GROUP, "Long", next.toString())
                dao.clearDownloadedContent(); dao.clearChapters(); dao.clearBooks()
                clearHistory(null)
            }
            writeLibrary()
            for (book in books) {
                if (dao.get(book.bookId) != null) continue
                dao.put(book.copy(generation = generation(), attempt = "", phase = "partial"))
                chapters.filter { it.bookId == book.bookId }.forEach { chapter ->
                    if (database.chapterContentDao().getId(chapter.id) != null) dao.put(chapter)
                }
            }
            if (legacy) database.userDataDao().remove(MIGRATED)
            migrateLegacy()
            beforeCommit()
        }
        // Cleanup is recoverable maintenance after the commit, never a failed restore.
        if (overwrite) runCatching { clearCachedImages(images) }.onFailure {
            Log.w("BookDownloadStore", "Could not evict retired image cache", it)
        }
        runCatching { removeRetiredGenerations() }.onFailure {
            Log.w("BookDownloadStore", "Could not clean retired downloads after restore", it)
        }
    } }

    /** Retry abandoned staging/retired file cleanup after process recreation. Keep every live owner. */
    private suspend fun removeRetiredGenerations() {
        val keep = dao.getAll().map { it.generation }.toSet() + generation()
        root.listFiles()?.forEach { directory ->
            val generation = directory.name.toLongOrNull() ?: return@forEach
            if (generation !in keep) runCatching {
                check(directory.deleteRecursively()) { "Could not remove retired download generation" }
            }.onFailure { Log.w("BookDownloadStore", "Could not clean retired downloads", it) }
        }
    }

    /** Old completed CACHE records establish intent; ordinary reading hits do not become downloads. */
    private suspend fun migrateLegacy() {
        if (database.userDataDao().get(MIGRATED) != null) return
        val entries = database.userDataDao().get(UserDataPath.CompletedDownloadBookList.path).orEmpty().split(',')
        for (entry in entries) {
            val fields = entry.trim().split('|')
            if (fields.size != 2 || fields[0] != DownloadType.CACHE.name) continue
            val book = runCatching { BookIdentity.book(fields[1]) }.getOrNull() ?: continue
            if (dao.get(book.storageKey) != null) continue
            val volumes = database.bookVolumesDao().getBookVolumes(book.storageKey) ?: continue
            val cover = database.bookInformationDao().get(book.storageKey)?.coverUri?.toString().orEmpty()
            val owner = BookDownloadEntity(book.storageKey, directoryHash = downloadDirectoryHash(volumes),
                generation = generation(), coverUri = cover)
            val savedChapters = mutableListOf<DownloadedChapterEntity>()
            for (chapter in volumes.volumes.flatMap { it.chapters }) {
                val body = database.chapterContentDao().get(chapter.id) ?: continue
                val images = mutableListOf<String>()
                decoder.getDataFromJsonObject(body.content) { if (it is ImageComponentData) images += it.uri.toString() }
                images.distinct().forEach { retainLegacyImage(owner, SourceImage(book, it)) }
                savedChapters += DownloadedChapterEntity(chapter.id, book.storageKey, "", Json.encodeToString(images.distinct()))
            }
            if (cover.isNotEmpty()) retainLegacyImage(owner, SourceImage(book, cover, true))
            // If an image copy fails, leave this book eligible for migration on the next attempt.
            database.withTransaction {
                dao.put(owner)
                savedChapters.forEach { dao.put(it) }
            }
        }
        database.userDataDao().insert(MIGRATED, GROUP, "Boolean", "true")
    }

    private fun retainLegacyImage(owner: BookDownloadEntity, image: SourceImage) {
        val bytes = cachedImageBytes(image, null) ?: return
        writeImage(imageFile(image.book, owner.generation, image.uri, image.cover), bytes)
    }

    private fun cachedImageBytes(image: SourceImage, cacheKey: String?): ByteArray? {
        val uri = Uri.parse(image.uri)
        if (uri.scheme in setOf("file", "content", "android.resource"))
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val key = cacheKey ?: imageKeys.getString(sourceImageCacheKey(image, ""), null) ?: return null
        val cache = SingletonImageLoader.get(context).diskCache ?: return null
        return cache.openSnapshot(key)?.use { snapshot -> cache.fileSystem.read(snapshot.data) { readByteArray() } }
    }
}
