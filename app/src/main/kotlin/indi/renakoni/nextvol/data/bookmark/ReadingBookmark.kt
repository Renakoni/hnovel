package indi.renakoni.nextvol.data.bookmark

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject

@Serializable
@Entity(tableName = "reading_bookmark", indices = [Index(value = ["bookId", "chapterId", "componentIndex", "offset", "fingerprint"], unique = true)])
data class ReadingBookmark(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val componentIndex: Int,
    val offset: Int,
    val fingerprint: String,
    val preview: String,
    val progress: Float,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun validate() {
        val book = SourceBookId.fromStorageKey(bookId)
        require(SourceChapterId.fromStorageKey(chapterId).book == book)
        require(UUID.fromString(id).toString() == id)
        require(componentIndex >= 0 && offset >= 0 && fingerprint.matches(Regex("[0-9a-f]{64}")))
        require(progress.isFinite() && progress in 0f..1f && preview.length <= 160)
    }
}

@Dao
interface ReadingBookmarkDao {
    @Query("SELECT * FROM reading_bookmark WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC")
    fun observe(bookId: String): Flow<List<ReadingBookmark>>

    @Query("SELECT * FROM reading_bookmark")
    suspend fun all(): List<ReadingBookmark>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: ReadingBookmark): Long

    @Query("DELETE FROM reading_bookmark WHERE id = :id AND bookId = :bookId")
    suspend fun delete(bookId: String, id: String)

    @Query("DELETE FROM reading_bookmark WHERE bookId IN (:bookIds)")
    suspend fun deleteBooks(bookIds: List<String>)

    @Query("DELETE FROM reading_bookmark")
    suspend fun clear()
}

class ReadingBookmarkRepository @Inject constructor(database: NextVolDatabase) {
    private val dao = database.readingBookmarkDao()
    fun observe(book: SourceBookId) = dao.observe(book.storageKey)
    suspend fun add(bookmark: ReadingBookmark): Boolean {
        bookmark.validate()
        return dao.insert(bookmark) != -1L
    }
    suspend fun delete(book: SourceBookId, id: String) = dao.delete(book.storageKey, id)
}
