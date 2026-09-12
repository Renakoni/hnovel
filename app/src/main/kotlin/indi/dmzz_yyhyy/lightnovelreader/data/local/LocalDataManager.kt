package indi.dmzz_yyhyy.lightnovelreader.data.local

import androidx.room.withTransaction
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.validateIdentities
import android.util.Log
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.asErr
import com.github.michaelbull.result.runCatching
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.AppLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookInformationDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookRecordDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookVolumesDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookshelfDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterContentDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.DailyCountDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.FormattingRuleDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserReadingDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.storage.StorageUsageRepository
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatisticsWriteCoordinator
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("OPT_IN_USAGE")
@Singleton
class LocalDataManager @Inject constructor(
    private val database: LightNovelReaderDatabase,
    private val bookBookInformationDao: BookInformationDao,
    private val bookRecordDao: BookRecordDao,
    private val dailyCountDao: DailyCountDao,
    private val bookshelfDao: BookshelfDao,
    private val chapterContentDao: ChapterContentDao,
    private val bookVolumesDao: BookVolumesDao,
    private val formattingRuleDao: FormattingRuleDao,
    private val userReadingDataDao: UserReadingDataDao,
    private val userDataDao: UserDataDao,
    private val storageUsageRepository: StorageUsageRepository,
    private val statisticsWriteCoordinator: StatisticsWriteCoordinator,
    private val statsRepository: StatsRepository
) {
    companion object {
        const val TAG = "LocalDataManager"
    }

    val currentAppDataVersion = 1
    // Library-wide lists travel with a backup; they are not a selected source's settings.
    private val libraryUserDataPaths = setOf(UserDataPath.ReadingBooks.path,
        UserDataPath.CompletedDownloadBookList.path, UserDataPath.Search.History.path)

    suspend fun exportAppLocalData(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true
    ): Result<AppLocalData, Throwable> {
        val localDataList = mutableListOf<LocalData>()
        exportCurrentLocalData(
            localBookCache, bookshelf, readingRecord, settings
        ).let {
            it.component1() ?: return it.asErr()
        }.let(localDataList::add)
        val globalLocalData = LocalData.empty()
            .copy(userDataEntities = if (settings) userDataDao.getAllEntities().filter {
                !libraryUserDataPaths.contains(it.path) &&
                        it.path != UserDataPath.Settings.Data.StorageUsageSnapshot.path
            }
            else emptyList())
        return Ok(
            AppLocalData(
                version = currentAppDataVersion,
                localDataList = localDataList,
                globalLocalData = globalLocalData
            )
        )
    }

    suspend fun exportCurrentLocalData(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true
    ): Result<LocalData, Throwable> {
        val exportOptionLocalData = ExportOptionLocalData(
            bookBookInformationDao = bookBookInformationDao,
            bookRecordDao = bookRecordDao,
            dailyCountDao = dailyCountDao,
            bookshelfDao = bookshelfDao,
            chapterContentDao = chapterContentDao,
            bookVolumesDao = bookVolumesDao,
            formattingRuleDao = formattingRuleDao,
            userReadingDataDao = userReadingDataDao,
            userDataDao = userDataDao,
            libraryUserDataPaths = libraryUserDataPaths
        ).apply {
            this.localBookCache.enable = localBookCache
            this.bookshelf.enable = bookshelf
            this.readingRecord.enable = readingRecord
            this.settings.enable = settings
        }

        return runCatching {
            statisticsWriteCoordinator.withLock { database.withTransaction { exportOptionLocalData.solve() } }
        }.andThen {
            Ok(
                LocalData(
                    bookInformationEntities = exportOptionLocalData.bookInformationEntities,
                    bookRecordEntities = exportOptionLocalData.bookRecordEntities,
                    dailyCountEntities = exportOptionLocalData.dailyCountEntities,
                    bookshelfEntities = exportOptionLocalData.bookshelfEntities,
                    bookshelfBookMetadataEntities = exportOptionLocalData.bookshelfBookMetadataEntities,
                    chapterContentEntities = exportOptionLocalData.chapterContentEntities,
                    chapterInformationEntities = exportOptionLocalData.chapterInformationEntities,
                    formattingRuleEntities = exportOptionLocalData.formattingRuleEntities,
                    userReadingDataEntities = exportOptionLocalData.userReadingDataEntities,
                    volumeEntities = exportOptionLocalData.volumeEntities,
                    userDataEntities = exportOptionLocalData.userDataEntities
                )
            )
        }
    }

    suspend fun importAppLocalData(appLocalData: AppLocalData): Result<Unit, Throwable> {
        if (currentAppDataVersion != appLocalData.version) {
            Log.e(TAG, "Unsupported data versions")
            return Err(Error("Unsupported data versions"))
        }
        validateBackup(appLocalData)
        importLocalDataToDatabase(appLocalData.globalLocalData)
        for (localData in appLocalData.localDataList) {
            importLocalData(localData).let {
                it.component1() ?: return it.asErr()
            }
        }
        storageUsageRepository.invalidateSnapshot()
        return Ok(Unit)
    }

    suspend fun importLocalData(localData: LocalData): Result<Unit, Throwable> =
        importLocalDataToDatabase(localData)

    fun validateBackup(appLocalData: AppLocalData) {
        require(appLocalData.version == currentAppDataVersion) { "Unsupported data version" }
        appLocalData.globalLocalData.validateIdentities()
        appLocalData.localDataList.forEach { it.validateIdentities() }
    }

    suspend fun importLocalDataToDatabase(localData: LocalData): Result<Unit, Throwable> {
        localData.validateIdentities()
        return statisticsWriteCoordinator.withLock { database.withTransaction {
          for (entity in localData.bookInformationEntities) {
            bookBookInformationDao.insert(
                bookBookInformationDao.getEntity(entity.id)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.bookRecordEntities) {
            val merged = bookRecordDao
                .getBookRecordByIdAndDate(entity.bookId, entity.date)
                ?.merge(entity)
                ?: entity
            bookRecordDao.insertBookRecord(merged)
          }
          for (entity in localData.dailyCountEntities) {
            val merged = dailyCountDao.getEntity(entity.date)?.merge(entity) ?: entity
            dailyCountDao.insert(merged)
          }
          for (entity in localData.bookshelfEntities) {
            bookshelfDao.insertBookshelf(
                bookshelfDao.getBookshelf(entity.id)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.bookshelfBookMetadataEntities) {
            bookshelfDao.insertBookshelfBookMetadata(
                bookshelfDao.getBookshelfBookMetadataEntity(
                    entity.id
                )?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.chapterContentEntities) {
            chapterContentDao.update(chapterContentDao.get(entity.id)?.let(entity::merge) ?: entity)
          }
          for (entity in localData.chapterInformationEntities) {
            bookVolumesDao.insertChapterInformationEntities(
                bookVolumesDao.getChapterInformationEntity(
                    entity.id
                )?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.volumeEntities) {
            bookVolumesDao.insertVolume(
                bookVolumesDao.getVolumeEntity(entity.volumeId)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.formattingRuleEntities) {
            formattingRuleDao.update(
                formattingRuleDao.getBookRuleEntity(entity.id)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.userReadingDataEntities) {
            userReadingDataDao.insert(
                userReadingDataDao.getEntity(entity.id)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.userDataEntities) {
            userDataDao.insert(userDataDao.getEntity(entity.path)?.let(entity::merge) ?: entity)
          }
          storageUsageRepository.invalidateSnapshot()
          Ok(Unit)
        } }
    }

    /** Explicit overwrite restore only. Source registration and browsing never call this. */
    suspend fun cleanDatabaseWithoutGlobalUserData() {
        statsRepository.withStatisticsResetLock {
          bookBookInformationDao.clear()
          bookRecordDao.clear()
          dailyCountDao.clear()
          bookshelfDao.clear()
          bookVolumesDao.clear()
          chapterContentDao.clear()
          formattingRuleDao.clear()
          userReadingDataDao.clear()

          for (entity in userDataDao.getAllEntities()) {
              if (!libraryUserDataPaths.contains(entity.path)) continue
              userDataDao.remove(entity.path)
          }
          runCatching {
              storageUsageRepository.invalidateSnapshot()
          }
        }
    }

}
