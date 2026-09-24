package indi.renakoni.nextvol.data.book

import android.app.Application
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import hnovel.content.LoginRefreshTarget
import indi.renakoni.nextvol.data.web.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReadingPanelRefreshTest {
    @Test fun declaredTargetsUseRealRepositoriesAndCapturedRuntimeWithoutShelfWrites() = runBlocking {
        val registry = WebSourceRegistry()
        val book = SourceBookId(Identifier("fixture", "reading-panel"), "A")
        val chapter = SourceChapterId(book, "chapter")
        val calls = mutableListOf<String>()
        val metadata = SourceMetadata(WebDataSourceItem(book.sourceId, "fixture", ""), emptySet())
        val source = object : WebBookDataSource by EmptyWebDataSource {
            override val id = book.sourceId
            override val permits = 2
            override suspend fun getBookInformation(id: String) = Ok(BookInformation(id, "Updated", author = "Author",
                description = "Description", publishingHouse = "", wordCount = WordCount(1),
                lastUpdated = java.time.LocalDateTime.MIN, isComplete = false)).also { calls += "info:$id" }
            override suspend fun getBookVolumes(id: String) = Ok(BookVolumes(id, listOf(Volume("volume", "Volume", listOf(ChapterInformation("chapter", "Chapter")))))).also { calls += "toc:$id" }
            override suspend fun getChapterContent(chapterId: String, bookId: String) = Ok(ChapterContent(chapterId, "Updated", JsonObject(emptyMap()))).also { calls += "content:$bookId:$chapterId" }
        }
        registry.register(source, metadata)
        val fixture = BookRepositoryFixture()
        coEvery { fixture.local.updateBookInformation(any()) } just Runs
        coEvery { fixture.local.updateBookVolumes(any()) } just Runs
        coEvery { fixture.local.updateChapterContent(any()) } just Runs
        coEvery { fixture.bookshelves.getBookshelfBookMetadata(any()) } returns null
        val chapters = ChapterRepository(registry, fixture.local, fixture.text, fixture.localBooks, fixture.downloads)
        val books = BookRepository(fixture.local, fixture.bookshelves, fixture.text, fixture.workManager, chapters,
            BookReadingDataRepository(fixture.local), registry, fixture.downloads, fixture.localBooks)
        every { fixture.text.processBookVolumes(any()) } answers { firstArg<() -> BookVolumes>().invoke() }
        val refresh = ReadingPanelRefresh(books, chapters, fixture.text)
        try {
            val runtime = (registry.resolve(book.sourceId) as SourceResolution.Ready).runtime
            assertTrue(refresh.refresh(book, chapter, runtime, emptySet()).isOk)
            assertTrue(calls.isEmpty())
            val info = refresh.refresh(book, chapter, runtime, setOf(LoginRefreshTarget.BookInformation)).get()!!
            assertNull(info.volumes); assertFalse(info.contentChanged)
            assertEquals(listOf("info:A"), calls)
            calls.clear()
            val updated = refresh.refresh(book, chapter, runtime, setOf(LoginRefreshTarget.Directory, LoginRefreshTarget.Content)).get()!!
            assertEquals(book.storageKey, updated.volumes!!.bookId)
            assertTrue(updated.contentChanged)
            assertEquals(listOf("toc:A", "content:A:chapter"), calls)
            coVerify(exactly = 1) { fixture.local.updateBookInformation(match { it.id == book.storageKey }) }
            coVerify(exactly = 1) { fixture.local.updateBookVolumes(any()) }
            coVerify(exactly = 1) { fixture.local.updateChapterContent(match { it.id == chapter.storageKey }) }
            coVerify(exactly = 1) { fixture.bookshelves.getBookshelfBookMetadata(book.storageKey) }
            confirmVerified(fixture.bookshelves)
            registry.unregister(book.sourceId)
            registry.register(source, metadata)
            calls.clear()
            val stale = refresh.refresh(book, chapter, runtime, LoginRefreshTarget.entries.toSet())
            assertTrue(stale.isErr)
            assertTrue(calls.isEmpty())
        } finally { registry.unregister(book.sourceId) }
    }
}
