package indi.dmzz_yyhyy.lightnovelreader.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.book.ChapterRepository
import indi.dmzz_yyhyy.lightnovelreader.data.book.ChapterSource

@Module
@InstallIn(SingletonComponent::class)
abstract class BookAccessModule {
    @Binds
    abstract fun bindChapterSource(repository: ChapterRepository): ChapterSource

    @Binds
    abstract fun bindBookReadingData(repository: BookReadingDataRepository): BookReadingDataAccess
}
