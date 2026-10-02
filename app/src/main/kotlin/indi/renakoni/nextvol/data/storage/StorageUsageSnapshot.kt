package indi.renakoni.nextvol.data.storage

import kotlinx.serialization.Serializable

@Serializable
data class StorageUsageSnapshot(
    val totalBytes: Long = 0L,
    val appBytes: Long = 0L,
    val databaseDiskBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val otherFileBytes: Long = 0L,
    val allBookMetadataBytes: Long = 0L,
    val orphanChapterInfoBytes: Long = 0L,
    val orphanChapterContentBytes: Long = 0L,
    val books: List<BookStorageUsage> = emptyList(),
    val calculatedAt: Long = 0L,
    val downloadImageBytes: Long = 0L,
    val importedFileBytes: Long = 0L,
    val readingContentBytes: Long = 0L,
    val imageCacheBytes: Long = 0L,
    val downloadedContentBytes: Long = 0L,
    val preparationBytes: Long = 0L,
    val downloadedBookCount: Int = 0,
    val downloadedChapterCount: Int = 0,
    val preparingChapterCount: Int = 0,
    val unfinishedDownloadCount: Int = 0,
    val schemaVersion: Int = 0,
) {
    val readingCacheBytes get() = readingContentBytes + imageCacheBytes
    val downloadBytes get() = downloadedContentBytes + preparationBytes + downloadImageBytes
}
