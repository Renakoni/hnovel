package indi.renakoni.nextvol.data.book

import io.nightfish.lightnovelreader.api.book.UserReadingData

/** Per-book reading data needed by reading modes and event recording. */
interface BookReadingDataAccess {
    suspend fun getUserReadingData(bookId: String): UserReadingData
    suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData)
    fun progressRevision(): Long
    suspend fun updateChapterProgress(
        bookId: String, chapterId: String, revision: Long,
        update: (UserReadingData) -> UserReadingData,
    ): Boolean
}
