package indi.renakoni.nextvol.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import indi.renakoni.nextvol.data.local.room.entity.BookAliasEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookAliasDao {
    @Query("SELECT canonicalId FROM book_alias WHERE id = :id")
    suspend fun get(id: String): String?

    @Query("SELECT canonicalId FROM book_alias WHERE id = :id")
    fun observe(id: String): Flow<String?>

    @Query("SELECT * FROM book_alias")
    suspend fun all(): List<BookAliasEntity>

    @Insert
    suspend fun insert(alias: BookAliasEntity)

    @Query("UPDATE book_alias SET canonicalId = :target WHERE canonicalId = :previous")
    suspend fun retarget(previous: String, target: String)

    @Query("DELETE FROM book_alias")
    suspend fun clear()
}
