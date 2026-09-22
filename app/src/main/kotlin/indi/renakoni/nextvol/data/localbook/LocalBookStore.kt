package indi.renakoni.nextvol.data.localbook

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.BookshelfEntity
import indi.renakoni.nextvol.data.local.room.entity.ImportedBookEntity
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.StringListUserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class LocalBookFormat { TXT, EPUB }

class LocalBookDraft internal constructor(
    val book: SourceBookId,
    val originalName: String,
    val format: LocalBookFormat,
    internal val directory: File,
) {
    internal val original get() = File(directory, "original.${format.name.lowercase()}")
}

@Serializable
private data class LocalChapterIndex(val title: String, val volume: String)

@Serializable
private data class LocalBookIndex(
    val title: String,
    val author: String,
    val description: String,
    val publisher: String,
    val cover: String?,
    val importedAt: String,
    val wordCount: Int,
    val chapters: List<LocalChapterIndex>,
)

/** Imported originals and parsed files are never reading cache or network source data. */
@Singleton
class LocalBookStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: NextVolDatabase,
) {
    private val root = File(context.filesDir, "local-books")
    private val lock = Mutex()
    private var recovered = false
    private val drafts = mutableSetOf<File>()

    companion object {
        val SOURCE = Identifier("nextvol", "local")
        fun isLocal(book: SourceBookId) = book.sourceId == SOURCE
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }

    suspend fun stage(uri: Uri): LocalBookDraft {
        var staged: LocalBookDraft? = null
        try {
            return withContext(Dispatchers.IO) { lock.withLock {
                recoverLocked()
                val name = (context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: uri.lastPathSegment.orEmpty()).substringAfterLast('/').substringAfterLast('\\')
                val mime = context.contentResolver.getType(uri)
                val format = when {
                    name.endsWith(".epub", true) || mime == "application/epub+zip" -> LocalBookFormat.EPUB
                    name.endsWith(".txt", true) || mime == "text/plain" -> LocalBookFormat.TXT
                    else -> throw LocalBookImportException(LocalBookImportReason.UnsupportedFormat, "Only EPUB and TXT files are supported.")
                }
                val book = SourceBookId(SOURCE, UUID.randomUUID().toString())
                val directory = File(root, "pending-${book.remoteId}")
                requireImport(directory.mkdirs(), LocalBookImportReason.Storage) { "Cannot create the import directory." }
                val draft = LocalBookDraft(book, name, format, directory)
                staged = draft
                drafts += directory
                requireImportNotNull(context.contentResolver.openInputStream(uri), LocalBookImportReason.FileAccess) { "Cannot open the selected file." }.use { input ->
                    draft.original.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            requireImport(size <= MAX_FILE_BYTES && (format != LocalBookFormat.TXT || size <= 16L * 1024 * 1024), LocalBookImportReason.FileTooLarge) {
                                "The selected file exceeds the import size limit."
                            }
                            output.write(buffer, 0, count)
                        }
                        requireImport(size > 0, LocalBookImportReason.EmptyFile) { "The selected file is empty." }
                    }
                }
                draft
            } }
        } catch (failure: Exception) {
            staged?.let { discard(it) }
            throw failure
        }
    }

    suspend fun preview(draft: LocalBookDraft, encoding: String? = null, rule: String = TxtBookParser.DEFAULT_RULE): ParsedLocalBook =
        withContext(Dispatchers.IO) { lock.withLock {
            requireImport(draft.directory in drafts, LocalBookImportReason.SessionExpired) { "This import session is no longer available." }
            currentCoroutineContext().ensureActive()
            val title = draft.originalName.substringBeforeLast('.').ifBlank { "Local book" }
            val parsed = when (draft.format) {
                LocalBookFormat.TXT -> TxtBookParser.parse(draft.original.readBytes(), title, encoding, rule)
                LocalBookFormat.EPUB -> EpubBookParser.parse(draft.original, File(draft.directory, "assets"), title)
            }
            currentCoroutineContext().ensureActive()
            parsed
        } }

    suspend fun discard(draft: LocalBookDraft) = withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
        if (drafts.remove(draft.directory)) deleteDirectory(draft.directory)
    } }

    /** Confirmation cannot be cancelled halfway through publishing files and the Room transaction. */
    suspend fun publish(draft: LocalBookDraft, parsed: ParsedLocalBook, title: String, shelfId: Int?): Pair<SourceBookId, Int> =
        withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
            requireImport(draft.directory in drafts, LocalBookImportReason.SessionExpired) { "This import session is no longer available." }
            requireImport(title.isNotBlank() && title.length <= 200, LocalBookImportReason.InvalidTitle) { "Enter a book title of at most 200 characters." }
            val index = LocalBookIndex(title.trim(), parsed.author, parsed.description, parsed.publishingHouse,
                parsed.coverPath, LocalDateTime.now().toString(),
                parsed.chapters.sumOf { chapter -> chapter.blocks.filterIsInstance<LocalBookBlock.Text>().sumOf { it.value.length } },
                parsed.chapters.map { LocalChapterIndex(it.title, it.volume) })
            val directory = File(root, draft.book.fileKey)
            var moved = false
            try {
                parsed.chapters.forEachIndexed { number, chapter ->
                    File(draft.directory, "$number.json").writeText(Json.encodeToString(chapter))
                }
                File(draft.directory, "index.json").writeText(Json.encodeToString(index))
                requireImport(draft.directory.renameTo(directory), LocalBookImportReason.Storage) { "Cannot publish the imported files." }
                moved = true
                val target = database.withTransaction {
                    val shelves = database.bookshelfDao()
                    val shelf = if (shelfId == null) {
                        requireImport(shelves.getAllBookshelfIds().isEmpty(), LocalBookImportReason.ShelfChanged) { "The selected bookshelf is no longer available." }
                        BookshelfEntity(0, context.getString(R.string.local_bookshelf_name), BookshelfSortType.Default.key,
                            autoCache = false, systemUpdateReminder = false, allBookIds = emptyList(),
                            pinnedBookIds = emptyList(), updatedBookIds = emptyList())
                    } else requireImportNotNull(shelves.getBookshelf(shelfId), LocalBookImportReason.ShelfChanged) { "The selected bookshelf is no longer available." }
                    database.importedBookDao().insert(ImportedBookEntity(draft.book.storageKey))
                    val info = information(draft.book, index, directory)
                    database.bookInformationDao().insert(info)
                    database.bookVolumesDao().insertVolume(info.id, volumes(draft.book, index))
                    parsed.chapters.forEachIndexed { number, chapter ->
                        database.chapterContentDao().cache(content(draft.book, index, directory, number, chapter))
                    }
                    shelves.insertBookshelf(shelf.copy(allBookIds = shelf.allBookIds + info.id))
                    shelves.addBookshelfMetadata(info.id, info.lastUpdated, listOf(shelf.id))
                    shelf.id
                }
                drafts.remove(draft.directory)
                draft.book to target
            } catch (failure: Exception) {
                if (moved) {
                    deleteDirectory(directory)
                    drafts.remove(draft.directory)
                }
                throw failure
            }
        } }

    suspend fun contains(book: SourceBookId): Boolean = withContext(Dispatchers.IO) {
        isLocal(book) && database.importedBookDao().contains(book.storageKey) && File(root, "${book.fileKey}/index.json").isFile
    }

    suspend fun readInformation(book: SourceBookId) = read(book) { index, directory -> information(book, index, directory) }
    suspend fun readVolumes(book: SourceBookId) = read(book) { index, _ -> volumes(book, index) }
    suspend fun readChapter(chapter: SourceChapterId) = read(chapter.book) { index, directory ->
        val number = chapter.remoteId.toIntOrNull()
        require(number != null && number.toString() == chapter.remoteId && number in index.chapters.indices) { "Unknown local chapter." }
        content(chapter.book, index, directory, number, Json.decodeFromString<LocalBookChapter>(File(directory, "$number.json").readText()))
    }

    private suspend fun <T> read(book: SourceBookId, block: (LocalBookIndex, File) -> T): Result<T, WebRequestError> =
        withContext(Dispatchers.IO) { lock.withLock {
            try {
                recoverLocked()
                require(isLocal(book) && database.importedBookDao().contains(book.storageKey)) { "The original local file is not on this device. Import the EPUB or TXT file again." }
                val directory = File(root, book.fileKey)
                Ok(block(Json.decodeFromString(File(directory, "index.json").readText()), directory))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Err(WebRequestError(context.getString(R.string.local_book_unavailable),
                    context.getString(R.string.local_book_reimport), failure))
            }
        } }

    /** A deliberate deletion from Book Manager removes this copy from every shelf, never other copies. */
    suspend fun delete(book: SourceBookId) = withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
        require(isLocal(book))
        recoverLocked()
        val key = book.storageKey
        val savedIndex = runCatching {
            Json.decodeFromString<LocalBookIndex>(File(root, "${book.fileKey}/index.json").readText())
        }.getOrNull()
        database.withTransaction {
            val chapterIds = (database.bookVolumesDao().getVolumeEntitiesByBookId(key).flatMap { it.chapterIds } +
                savedIndex?.chapters.orEmpty().indices.map { SourceChapterId(book, it.toString()).storageKey }).distinct()
            chapterIds.chunked(500).forEach {
                database.chapterContentDao().deleteByIds(it)
                database.bookVolumesDao().deleteChapterInformationByIds(it)
            }
            database.bookVolumesDao().deleteByBookIds(listOf(key))
            database.bookInformationDao().deleteByIds(listOf(key))
            database.userReadingDataDao().deleteByIds(listOf(key))
            StringListUserData(UserDataPath.ReadingBooks.path, database.userDataDao()).update { it - key }
            for (shelf in database.bookshelfDao().getAllBookshelves()) if (key in shelf.allBookIds) {
                database.bookshelfDao().insertBookshelf(shelf.copy(allBookIds = shelf.allBookIds - key,
                    pinnedBookIds = shelf.pinnedBookIds - key, updatedBookIds = shelf.updatedBookIds - key))
            }
            database.bookshelfDao().deleteBookshelfBookMetadata(key)
            database.importedBookDao().delete(key)
        }
        deleteDirectory(File(root, book.fileKey))
    } }

    /** Overwrite-restoring a legacy backup can clear cache metadata, but cannot replace local originals. */
    suspend fun restoreMetadata() = withContext(Dispatchers.IO) { lock.withLock {
        recoverLocked()
        for (key in database.importedBookDao().allIds()) if (!database.bookInformationDao().has(key)) {
            try {
                val book = BookIdentity.book(key)
                val directory = File(root, book.fileKey)
                val index = Json.decodeFromString<LocalBookIndex>(File(directory, "index.json").readText())
                database.bookInformationDao().insert(information(book, index, directory))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                android.util.Log.e("LocalBookStore", "Cannot restore imported book metadata", failure)
                // Keep damaged copies visible in Book Manager so their owned files can be deleted.
                database.bookInformationDao().insert(BookInformation(id = key,
                    title = context.getString(R.string.local_book_unavailable), author = "",
                    description = context.getString(R.string.local_book_reimport), publishingHouse = "",
                    wordCount = WordCount(0), lastUpdated = LocalDateTime.now(), isComplete = true))
            }
        }
    } }

    suspend fun storedBytes(book: SourceBookId): Long = withContext(Dispatchers.IO) {
        if (isLocal(book)) File(root, book.fileKey).walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    private suspend fun recoverLocked() {
        if (recovered) return
        requireImport(root.isDirectory || root.mkdirs(), LocalBookImportReason.Storage) { "Cannot open the local library." }
        val owned = database.importedBookDao().allIds().map { BookIdentity.book(it).fileKey }.toSet()
        root.listFiles()?.filter { it.isDirectory && it.name !in owned && it !in drafts }?.forEach(::deleteDirectory)
        recovered = true
    }

    private fun deleteDirectory(directory: File) {
        require(directory.canonicalFile.parentFile == root.canonicalFile) { "Invalid local book directory." }
        requireImport(!directory.exists() || directory.deleteRecursively(), LocalBookImportReason.Storage) { "Cannot remove the local book files." }
    }

    private fun asset(directory: File, path: String): Uri {
        val file = File(directory, path).canonicalFile
        require(file.parentFile == File(directory, "assets").canonicalFile && file.isFile) { "A local book image is missing." }
        return file.toUri()
    }

    private fun information(book: SourceBookId, index: LocalBookIndex, directory: File) = BookInformation(
        id = book.storageKey, title = index.title, author = index.author, description = index.description,
        coverUri = index.cover?.let { asset(directory, it) } ?: Uri.EMPTY,
        publishingHouse = index.publisher, wordCount = WordCount(index.wordCount),
        lastUpdated = LocalDateTime.parse(index.importedAt), isComplete = true,
    )

    private fun volumes(book: SourceBookId, index: LocalBookIndex): BookVolumes {
        val volumes = mutableListOf<Volume>()
        index.chapters.forEachIndexed { number, chapter ->
            val volumeTitle = chapter.volume.ifBlank { index.title }
            val info = ChapterInformation(SourceChapterId(book, number.toString()).storageKey, chapter.title)
            if (volumes.lastOrNull()?.volumeTitle == volumeTitle) {
                volumes[volumes.lastIndex] = volumes.last().copy(chapters = volumes.last().chapters + info)
            } else volumes += Volume(BookIdentity.volumeKey(book, volumes.size.toString()), volumeTitle, listOf(info))
        }
        return BookVolumes(book.storageKey, volumes)
    }

    private fun content(book: SourceBookId, index: LocalBookIndex, directory: File, number: Int, chapter: LocalBookChapter): ChapterContent {
        val builder = ContentBuilder()
        if (chapter.blocks.isEmpty()) builder.simpleText(chapter.title)
        for (block in chapter.blocks) when (block) {
            is LocalBookBlock.Text -> builder.simpleText(block.value)
            is LocalBookBlock.Image -> builder.image(asset(directory, block.path))
        }
        return ChapterContent(SourceChapterId(book, number.toString()).storageKey, chapter.title, builder.build(),
            (number - 1).takeIf { it >= 0 }?.let { SourceChapterId(book, it.toString()).storageKey },
            (number + 1).takeIf { it < index.chapters.size }?.let { SourceChapterId(book, it.toString()).storageKey })
    }
}
