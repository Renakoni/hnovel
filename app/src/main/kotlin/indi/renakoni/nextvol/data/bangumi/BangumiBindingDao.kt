package indi.renakoni.nextvol.data.bangumi

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class BangumiLocalBook(val id: String, val title: String)

/** Bounded local audit history; no token, response body or private collection metadata. */
@Entity(tableName = "bangumi_sync_record", indices = [Index("accountId")])
data class BangumiSyncRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Int,
    val bookId: String,
    val bookTitle: String,
    val target: Int,
    val remote: Int,
    val status: BangumiSyncStatus,
    val timestamp: Long,
    val httpStatus: Int? = null,
    val pendingConfirmation: Boolean = false,
)

@Entity(tableName = "bangumi_binding", primaryKeys = ["accountId", "bookId"],
    indices = [Index(value = ["accountId", "subjectId"], unique = true)])
data class BangumiBindingEntity(val accountId: Int, val bookId: String, val subjectId: Int?, val data: String) {
    fun binding(): BangumiBinding = bangumiJson.decodeFromString(data)
    fun withBinding(value: BangumiBinding) = copy(data = bangumiJson.encodeToString(value))
}

@Dao
interface BangumiBindingDao {
    @Query("SELECT id, title FROM book_information WHERE EXISTS (SELECT 1 FROM user_reading_data WHERE user_reading_data.id = book_information.id) AND EXISTS (SELECT 1 FROM volume WHERE volume.book_id = book_information.id) ORDER BY title")
    suspend fun getReadingBooks(): List<BangumiLocalBook>

    @Query("SELECT * FROM bangumi_sync_record ORDER BY id DESC")
    fun observeRecords(): Flow<List<BangumiSyncRecord>>

    @Query("SELECT * FROM bangumi_sync_record WHERE accountId = :accountId ORDER BY id DESC")
    suspend fun getRecords(accountId: Int): List<BangumiSyncRecord>

    @Insert suspend fun insertRecord(record: BangumiSyncRecord)

    @Query("DELETE FROM bangumi_sync_record WHERE accountId = :accountId AND id NOT IN (SELECT id FROM bangumi_sync_record WHERE accountId = :accountId ORDER BY id DESC LIMIT 100)")
    suspend fun trimRecords(accountId: Int)

    @Query("SELECT * FROM bangumi_binding ORDER BY bookId")
    fun observeAll(): Flow<List<BangumiBindingEntity>>

    @Query("SELECT * FROM bangumi_binding WHERE accountId = :accountId")
    suspend fun getAll(accountId: Int): List<BangumiBindingEntity>

    @Query("SELECT * FROM bangumi_binding WHERE accountId = :accountId AND bookId = :bookId")
    suspend fun get(accountId: Int, bookId: String): BangumiBindingEntity?

    @Upsert suspend fun save(value: BangumiBindingEntity)

    @Query("DELETE FROM bangumi_binding WHERE accountId = :accountId AND bookId = :bookId")
    suspend fun delete(accountId: Int, bookId: String)
}
