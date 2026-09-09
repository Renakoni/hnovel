package indi.dmzz_yyhyy.lightnovelreader.data.local

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookInformationDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookVolumesDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterContentDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserReadingDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserReadingDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.data.book.bindReadingData
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceChapterId
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
    override suspend fun getBookInformation(id: String): BookInformation? = bookInformationDao.get(BookIdentity.bookKey(id))
    override suspend fun updateBookInformation(info: BookInformation) = bookInformationDao.insert(info.copy(id = BookIdentity.bookKey(info.id)))
    override suspend fun getBookVolumes(id: String): BookVolumes? = bookVolumesDao.getBookVolumes(BookIdentity.bookKey(id))
    override suspend fun updateBookVolumes(bookVolumes: BookVolumes) {
        val book = SourceBookId.fromStorageKey(bookVolumes.bookId)
        bookVolumes.volumes.forEach { volume ->
            BookIdentity.volumeRemoteId(volume.volumeId, book)
            volume.chapters.forEach { require(SourceChapterId.fromStorageKey(it.id).book == book) }
        }
        bookVolumesDao.insertVolume(bookVolumes.bookId, bookVolumes)
    }

    override suspend fun getChapterContent(id: String) = chapterContentDao.get(
        SourceChapterId.fromStorageKey(id).storageKey
    )?.let {
        ChapterContent(
            it.id,
            it.title,
            it.content,
            it.prevChapter.ifEmpty { null },
            it.nextChapter.ifEmpty { null }
        )
    }
    override suspend fun updateChapterContent(chapterContent: ChapterContent) {
        val chapter = SourceChapterId.fromStorageKey(chapterContent.id)
        listOfNotNull(chapterContent.prevChapter, chapterContent.nextChapter).forEach {
            require(SourceChapterId.fromStorageKey(it).book == chapter.book)
        }
        chapterContentDao.update(chapterContent)
    }

    override suspend fun getUserReadingData(id: String) = userReadingDataDao.getEntity(BookIdentity.bookKey(id)).let {
        it ?: return@let UserReadingData(BookIdentity.bookKey(id))
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

    fun getUserReadingDataFlow(id: String) = userReadingDataDao.getEntityFlow(BookIdentity.bookKey(id)).map {
        it ?: return@map UserReadingData(
            BookIdentity.bookKey(id),
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
        userReadingDataDao.update(BookIdentity.bookKey(id)) { entity ->
            val userReadingData = entity?.let {
                UserReadingData(
                    it.id,
                    it.lastReadTime.takeUnless { time -> time == LocalDateTime.MIN },
                    it.totalReadTime,
                    it.readingProgress,
                    it.lastReadChapterId.ifEmpty { null },
                    it.lastReadChapterTitle.ifEmpty { null },
                    it.currentChapterReadingProgressMap,
                    it.maxChapterReadingProgressMap
                )
            } ?: UserReadingData(BookIdentity.bookKey(id))
            val updated = update(userReadingData)
            val new = BookIdentity.book(id).bindReadingData(updated)
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
