package indi.renakoni.nextvol.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import indi.renakoni.nextvol.data.local.room.entity.ImportedBookEntity

@Dao
interface ImportedBookDao {
    @Insert
    suspend fun insert(book: ImportedBookEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM imported_book WHERE bookId = :bookId)")
    suspend fun contains(bookId: String): Boolean

    @Query("SELECT bookId FROM imported_book")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM imported_book WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}
