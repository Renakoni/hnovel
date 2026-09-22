package indi.renakoni.nextvol.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow
import androidx.room.Query
import indi.renakoni.nextvol.data.local.room.entity.ImportedBookEntity

@Dao
interface ImportedBookDao {
    @Insert
    suspend fun insert(book: ImportedBookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(book: ImportedBookEntity)

    @Query("SELECT * FROM imported_book WHERE bookId = :bookId")
    suspend fun get(bookId: String): ImportedBookEntity?

    @Query("SELECT * FROM imported_book WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<ImportedBookEntity?>

    @Query("SELECT * FROM imported_book")
    suspend fun all(): List<ImportedBookEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM imported_book WHERE bookId = :bookId)")
    suspend fun contains(bookId: String): Boolean

    @Query("SELECT bookId FROM imported_book")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM imported_book WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}
