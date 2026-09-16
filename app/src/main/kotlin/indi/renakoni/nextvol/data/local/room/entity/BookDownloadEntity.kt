package indi.renakoni.nextvol.data.local.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "book_download")
data class BookDownloadEntity(
    @PrimaryKey val bookId: String,
    val revision: String = "",
    val directoryHash: String = "",
    val phase: String = "partial",
    val generation: Long = 0,
    val attempt: String = "",
    val coverUri: String = "",
)

/** Ownership and the source-visible version of a successfully saved chapter. Body stays in Room. */
@Serializable
@Entity(tableName = "downloaded_chapter", indices = [Index("bookId")])
data class DownloadedChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val signature: String,
    val images: String = "[]",
)
