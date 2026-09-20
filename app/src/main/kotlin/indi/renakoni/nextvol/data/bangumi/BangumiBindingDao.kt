package indi.renakoni.nextvol.data.bangumi

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bangumi_binding", primaryKeys = ["accountId", "bookId"],
    indices = [Index(value = ["accountId", "subjectId"], unique = true)])
data class BangumiBindingEntity(val accountId: Int, val bookId: String, val subjectId: Int, val data: String) {
    fun binding(): BangumiBinding = bangumiJson.decodeFromString(data)
    fun withBinding(value: BangumiBinding) = copy(data = bangumiJson.encodeToString(value))
}

@Dao
interface BangumiBindingDao {
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
