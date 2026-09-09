package indi.dmzz_yyhyy.lightnovelreader.data.local

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookInformationDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookVolumesDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterContentDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserReadingDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserReadingDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceChapterId
import indi.dmzz_yyhyy.lightnovelreader.data.book.StorageKey
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.book.UserReadingData
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBookDataSource @Inject constructor(
    private val bookInformationDao: BookInformationDao,
    private val bookVolumesDao: BookVolumesDao,
    private val chapterContentDao: ChapterContentDao,
    private val userReadingDataDao: UserReadingDataDao
): LocalBookDataSourceApi {
    /** Source-qualified access used by new callers; legacy String methods remain a bridge. */
    suspend fun getSourceBookInformation(book: SourceBookId): BookInformation? =
        bookInformationDao.get(book.storageKey)?.copy(id = book.remoteId)

    suspend fun updateSourceBookInformation(book: SourceBookId, info: BookInformation) {
        require(info.id == book.remoteId) { "book response belongs to a different remote id" }
        bookInformationDao.insert(info.copy(id = book.storageKey))
    }

    suspend fun getSourceBookVolumes(book: SourceBookId): BookVolumes? =
        bookVolumesDao.getBookVolumes(book.storageKey)?.let { volumes ->
            volumes.copy(
                bookId = book.remoteId,
                volumes = volumes.volumes.map { volume ->
                    volume.copy(chapters = volume.chapters.map { chapter ->
                        chapter.copy(id = chapter.remoteChapterId(book))
                    })
                }
            )
        }

    suspend fun updateSourceBookVolumes(book: SourceBookId, volumes: BookVolumes) {
        require(volumes.bookId == book.remoteId) { "volume response belongs to a different remote book" }
        val stored = volumes.copy(
            bookId = book.storageKey,
            volumes = volumes.volumes.map { volume ->
                volume.copy(chapters = volume.chapters.map { chapter ->
                    chapter.copy(id = SourceChapterId(book, chapter.id).storageKey)
                })
            }
        )
        bookVolumesDao.insertVolume(book.storageKey, stored)
    }

    suspend fun getSourceChapterContent(chapter: SourceChapterId): ChapterContent? =
        chapterContentDao.get(chapter.storageKey)?.let { content ->
            ChapterContent(
                chapter.remoteId,
                content.title,
                content.content,
                content.prevChapter,
                content.nextChapter,
            )
        }

    suspend fun updateSourceChapterContent(chapter: SourceChapterId, content: ChapterContent) {
        require(content.id == chapter.remoteId) { "chapter response belongs to a different remote id" }
        chapterContentDao.update(
            content.copy(
                id = chapter.storageKey,
                prevChapter = content.prevChapter,
                nextChapter = content.nextChapter,
            )
        )
    }

    suspend fun getSourceUserReadingData(book: SourceBookId): UserReadingData =
        getUserReadingData(book.storageKey).decodeReadingData(book)

    suspend fun updateSourceUserReadingData(book: SourceBookId, update: (UserReadingData) -> UserReadingData) {
        updateUserReadingData(book.storageKey) { current ->
            update(current.decodeReadingData(book)).encodeReadingData(book)
        }
    }

    override suspend fun getBookInformation(id: String): BookInformation? = bookInformationDao.get(id)
    override suspend fun updateBookInformation(info: BookInformation) = bookInformationDao.insert(info)
    override suspend fun getBookVolumes(id: String): BookVolumes? = bookVolumesDao.getBookVolumes(id)
    override suspend fun updateBookVolumes(bookVolumes: BookVolumes) =
        bookVolumesDao.insertVolume(bookVolumes.bookId, bookVolumes)

    override suspend fun getChapterContent(id: String) = chapterContentDao.get(id)?.let {
        ChapterContent(
            it.id,
            it.title,
            it.content,
            it.prevChapter.ifEmpty { null },
            it.nextChapter.ifEmpty { null }
        )
    }
    override suspend fun updateChapterContent(chapterContent: ChapterContent) =
        chapterContentDao.update(chapterContent)

    override suspend fun getUserReadingData(id: String) = userReadingDataDao.getEntity(id).let {
        it ?: return@let UserReadingData(id)
        UserReadingData(
            it.id,
            if (it.lastReadTime == LocalDateTime.MIN) null else it.lastReadTime,
            it.totalReadTime,
            it.readingProgress,
            it.lastReadChapterId.ifEmpty { null },
            it.lastReadChapterTitle.ifEmpty { null },
            it.currentChapterReadingProgressMap,
            it.maxChapterReadingProgressMap

        )
    }

    fun getUserReadingDataFlow(id: String) = userReadingDataDao.getEntityFlow(id).map {
        it ?: return@map UserReadingData(
            id,
            null,
            0,
            0f,
            null,
            null,
            emptyMap(),
            emptyMap()
        )
        UserReadingData(
            it.id,
            if (it.lastReadTime == LocalDateTime.MIN) null else it.lastReadTime,
            it.totalReadTime,
            it.readingProgress,
            it.lastReadChapterId.ifEmpty { null },
            it.lastReadChapterTitle.ifEmpty { null },
            it.currentChapterReadingProgressMap,
            it.maxChapterReadingProgressMap
        )
    }

    override suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {
        userReadingDataDao.update(id) { entity ->
            val userReadingData = entity?.let {
                UserReadingData(
                    it.id,
                    it.lastReadTime,
                    it.totalReadTime,
                    it.readingProgress,
                    it.lastReadChapterId,
                    it.lastReadChapterTitle,
                    it.currentChapterReadingProgressMap,
                    it.maxChapterReadingProgressMap
                )
            } ?: UserReadingData(id)
            val new = update(userReadingData)
            UserReadingDataEntity(
                id = new.id,
                lastReadTime = new.lastReadTime ?: LocalDateTime.MIN,
                totalReadTime = new.totalReadTime,
                readingProgress = new.readingProgress,
                lastReadChapterId = new.lastReadChapterId ?: "",
                lastReadChapterTitle = new.lastReadChapterTitle ?: "",
                currentChapterReadingProgressMap = new.currentChapterReadingProgressMap,
                maxChapterReadingProgressMap = new.maxChapterReadingProgressMap
            )
        }
    }

    override suspend fun getAllUserReadingData(): List<UserReadingData> =
        userReadingDataDao.getAll().map {
            UserReadingData(
                it.id,
                it.lastReadTime,
                it.totalReadTime,
                it.readingProgress,
                it.lastReadChapterId,
                it.lastReadChapterTitle,
                it.currentChapterReadingProgressMap,
                it.maxChapterReadingProgressMap
            )
        }

    override suspend fun isChapterContentExists(id: String): Boolean =
        chapterContentDao.getId(id) != null

    override suspend fun clear() {
        userReadingDataDao.clear()
        bookInformationDao.clear()
        bookVolumesDao.clear()
        chapterContentDao.clear()
    }
}

private fun String?.decodeChapterId(book: SourceBookId): String? = this?.takeIf { it.isNotEmpty() }?.let { encoded ->
    StorageKey.decode(book.sourceId, encoded)?.let { value ->
        StorageKey.decodePair(value)?.takeIf { it.first == book.remoteId }?.second
    } ?: encoded
}

private fun UserReadingData.decodeReadingData(book: SourceBookId): UserReadingData = copy(
    id = book.remoteId,
    lastReadChapterId = lastReadChapterId?.decodeChapterId(book),
    currentChapterReadingProgressMap = currentChapterReadingProgressMap.mapKeys { it.key.decodeChapterId(book).orEmpty() },
    maxChapterReadingProgressMap = maxChapterReadingProgressMap.mapKeys { it.key.decodeChapterId(book).orEmpty() },
)

private fun UserReadingData.encodeReadingData(book: SourceBookId): UserReadingData = copy(
    id = book.storageKey,
    lastReadChapterId = lastReadChapterId?.let { SourceChapterId(book, it).storageKey },
    currentChapterReadingProgressMap = currentChapterReadingProgressMap.mapKeys { SourceChapterId(book, it.key).storageKey },
    maxChapterReadingProgressMap = maxChapterReadingProgressMap.mapKeys { SourceChapterId(book, it.key).storageKey },
)

private fun io.nightfish.lightnovelreader.api.book.ChapterInformation.remoteChapterId(book: SourceBookId): String =
    StorageKey.decode(book.sourceId, id)?.let { value ->
        StorageKey.decodePair(value)?.takeIf { it.first == book.remoteId }?.second
    } ?: id
