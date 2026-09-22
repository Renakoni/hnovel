package indi.renakoni.nextvol.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Ownership of an imported original and its immutable parsed files, independent of reading cache. */
@Entity(tableName = "imported_book")
data class ImportedBookEntity(
    @PrimaryKey val bookId: String,
    @ColumnInfo(defaultValue = "''") val directoryName: String = "",
)
