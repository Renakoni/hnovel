package indi.dmzz_yyhyy.lightnovelreader.data.book

import io.nightfish.lightnovelreader.api.book.UserReadingData

/** Per-book reading data needed by reading modes and event recording. */
interface BookReadingDataAccess {
    suspend fun getUserReadingData(bookId: String): UserReadingData
    suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData)
}
