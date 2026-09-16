package indi.renakoni.nextvol.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.dao.BookInformationDao
import indi.renakoni.nextvol.data.local.room.dao.BookRecordDao
import indi.renakoni.nextvol.data.local.room.dao.BookVolumesDao
import indi.renakoni.nextvol.data.local.room.dao.BookshelfDao
import indi.renakoni.nextvol.data.local.room.dao.ChapterContentDao
import indi.renakoni.nextvol.data.local.room.dao.DailyCountDao
import indi.renakoni.nextvol.data.local.room.dao.FormattingRuleDao
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.local.room.dao.UserReadingDataDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DaoModule {
    @Singleton
    @Provides
    fun provideBookInformationDao(db: NextVolDatabase): BookInformationDao =
        db.bookInformationDao()

    @Singleton
    @Provides
    fun provideBookVolumesDao(db: NextVolDatabase): BookVolumesDao =
        db.bookVolumesDao()

    @Singleton
    @Provides
    fun provideChapterContentDao(db: NextVolDatabase): ChapterContentDao =
        db.chapterContentDao()

    @Singleton
    @Provides
    fun provideUserReadingDataDao(db: NextVolDatabase): UserReadingDataDao =
        db.userReadingDataDao()

    @Singleton
    @Provides
    fun provideUserDataDao(db: NextVolDatabase): UserDataDao =
        db.userDataDao()

    @Singleton
    @Provides
    fun provideBookshelfDao(db: NextVolDatabase): BookshelfDao =
        db.bookshelfDao()

    @Provides
    @Singleton
    fun provideBookRecordsDao(db: NextVolDatabase): BookRecordDao {
        return db.bookRecordDao()
    }

    @Provides
    @Singleton
    fun provideDailyCountDao(db: NextVolDatabase): DailyCountDao {
        return db.dailyCountDao()
    }

    @Provides
    @Singleton
    fun provideFormattingRuleDao(db: NextVolDatabase): FormattingRuleDao {
        return db.formattingRuleDao()
    }
}
