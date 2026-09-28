package indi.renakoni.nextvol.data.download

/** Older-version fixtures must remove v25 columns before exercising the real migration chain. */
internal fun restorePre25DownloadSchema(execSql: (String) -> Unit) {
    execSql("ALTER TABLE book_download RENAME TO book_download_v25")
    execSql("CREATE TABLE book_download (bookId TEXT NOT NULL PRIMARY KEY, revision TEXT NOT NULL, directoryHash TEXT NOT NULL, phase TEXT NOT NULL, generation INTEGER NOT NULL, attempt TEXT NOT NULL, coverUri TEXT NOT NULL)")
    execSql("INSERT INTO book_download SELECT bookId, revision, directoryHash, phase, generation, attempt, coverUri FROM book_download_v25")
    execSql("DROP TABLE book_download_v25")
}
