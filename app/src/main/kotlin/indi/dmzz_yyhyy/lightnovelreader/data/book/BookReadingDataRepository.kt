package indi.dmzz_yyhyy.lightnovelreader.data.book

import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import io.nightfish.lightnovelreader.api.book.UserReadingData
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookReadingDataRepository @Inject constructor(
    private val localBookDataSource: LocalBookDataSource,
) : BookReadingDataAccess {
    override suspend fun getUserReadingData(bookId: String): UserReadingData =
        localBookDataSource.getUserReadingData(bookId)

    suspend fun getUserReadingData(book: SourceBookId): UserReadingData =
        localBookDataSource.getUserReadingData(book.storageKey)

    fun getUserReadingDataFlow(bookId: String): Flow<UserReadingData> =
        localBookDataSource.getUserReadingDataFlow(bookId)

    suspend fun getAllUserReadingData(): List<UserReadingData> =
        localBookDataSource.getAllUserReadingData()

    override suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {
        localBookDataSource.updateUserReadingData(id, update)
    }

    suspend fun updateUserReadingData(book: SourceBookId, update: (UserReadingData) -> UserReadingData) {
        localBookDataSource.updateUserReadingData(book.storageKey, update)
    }
}
