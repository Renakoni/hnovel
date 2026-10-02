package indi.renakoni.nextvol.data.storage

import kotlinx.serialization.Serializable

@Serializable
data class BookStorageUsage(
    val bookId: String,
    val bookInformationBytes: Long,
    val volumeBytes: Long,
    val chapterInformationBytes: Long,
    val chapterContentBytes: Long,
    val downloadImageBytes: Long = 0L,
    val preparationBytes: Long = 0L,
) {
    val totalBytes: Long
        get() = bookInformationBytes + volumeBytes + chapterInformationBytes + chapterContentBytes + downloadImageBytes + preparationBytes
}
