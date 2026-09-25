package indi.renakoni.nextvol.data.work

import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.download.BookDownloadStore
import io.mockk.*
import kotlinx.coroutines.flow.first

/** Unit fixtures leave persistence to BookDownloadTest, which uses real Room and source images. */
internal fun exportDownloads(): BookDownloadStore = mockk<BookDownloadStore>(relaxed = true).also { store ->
    coEvery { store.withBookOperation<Any?>(any(), any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    coEvery { store.begin(any(), any(), any()) } answers { BookDownloadStore.Attempt(firstArg(), secondArg(), thirdArg()) }
    coEvery { store.reusable(any(), any(), any()) } returns null
    coEvery { store.hasVersionedChapter(any(), any()) } returns false
    coEvery { store.revision(any()) } returns null
}

internal fun stubExportRepository(repository: BookRepository) {
    coEvery { repository.canonicalBook(any()) } answers { firstArg() }
    every { repository.sourceRevision(any()) } returns "1"
    coEvery { repository.exportInformation(any()) } coAnswers {
        repository.getBookInformationFlow(firstArg<indi.renakoni.nextvol.data.book.SourceBookId>().storageKey).first()
    }
    coEvery { repository.exportVolumes(any()) } coAnswers {
        repository.getBookVolumesFlow(firstArg<indi.renakoni.nextvol.data.book.SourceBookId>().storageKey).first()
    }
    coEvery { repository.exportChapter(any(), any()) } coAnswers {
        repository.getChapterContentFlow(secondArg(), firstArg<indi.renakoni.nextvol.data.book.SourceBookId>().storageKey).first()
    }
    coEvery { repository.exportContent(any(), any()) } answers { secondArg() }
    every { repository.exportMetadata(any()) } answers { firstArg() }
    every { repository.exportCatalog(any()) } answers { firstArg() }
}
