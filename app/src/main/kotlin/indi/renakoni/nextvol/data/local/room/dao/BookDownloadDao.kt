package indi.renakoni.nextvol.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.local.room.entity.DownloadedChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDownloadDao {
    @Query("select * from book_download where bookId = :bookId")
    suspend fun get(bookId: String): BookDownloadEntity?

    @Query("select * from book_download where bookId = :bookId")
    fun observe(bookId: String): Flow<BookDownloadEntity?>

    @Query("select * from book_download")
    suspend fun getAll(): List<BookDownloadEntity>

    @Query("select * from downloaded_chapter where id = :id")
    suspend fun chapter(id: String): DownloadedChapterEntity?

    @Query("select * from downloaded_chapter where bookId = :bookId")
    suspend fun chapters(bookId: String): List<DownloadedChapterEntity>

    @Query("select * from downloaded_chapter where bookId = :bookId")
    fun observeChapters(bookId: String): Flow<List<DownloadedChapterEntity>>

    @Query("select * from downloaded_chapter")
    suspend fun allChapters(): List<DownloadedChapterEntity>

    @Query("select chapter_content.id from chapter_content inner join downloaded_chapter on chapter_content.id = downloaded_chapter.id where downloaded_chapter.bookId = :bookId")
    suspend fun savedContentIds(bookId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(book: BookDownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(chapter: DownloadedChapterEntity)

    @Query("delete from chapter_content where id not in (select id from downloaded_chapter)")
    suspend fun clearReadingContent()

    @Query("delete from chapter_content where id in (select id from downloaded_chapter)")
    suspend fun clearDownloadedContent()

    @Query("delete from downloaded_chapter")
    suspend fun clearChapters()

    @Query("delete from book_download")
    suspend fun clearBooks()

    @Query("delete from chapter_content where id in (select id from downloaded_chapter where bookId in (:bookIds))")
    suspend fun deleteContent(bookIds: List<String>)

    @Query("delete from downloaded_chapter where bookId in (:bookIds)")
    suspend fun deleteChapters(bookIds: List<String>)

    @Query("delete from book_download where bookId in (:bookIds)")
    suspend fun deleteBooks(bookIds: List<String>)
}
