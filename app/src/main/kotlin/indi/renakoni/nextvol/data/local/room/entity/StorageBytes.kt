package indi.renakoni.nextvol.data.local.room.entity

data class StorageBytes(
    val id: String,
    val bytes: Long
)

data class DownloadPreparationBytes(
    val bookId: String,
    val bytes: Long,
    val chapters: Int,
)
