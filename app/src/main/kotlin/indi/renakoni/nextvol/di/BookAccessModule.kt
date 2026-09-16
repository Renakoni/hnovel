package indi.renakoni.nextvol.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.book.BookReadingDataRepository
import indi.renakoni.nextvol.data.book.ChapterRepository
import indi.renakoni.nextvol.data.book.ChapterSource

@Module
@InstallIn(SingletonComponent::class)
abstract class BookAccessModule {
    @Binds
    abstract fun bindChapterSource(repository: ChapterRepository): ChapterSource

    @Binds
    abstract fun bindBookReadingData(repository: BookReadingDataRepository): BookReadingDataAccess
}
