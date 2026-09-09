package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import android.app.Application
import androidx.lifecycle.ViewModelStore
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.ExpandedPageViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SourceTagPageTest {
    @Test fun bookTagUsesItsOwnPageAndBooksWithoutReadingTheBrowsingProvider() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val registry = WebSourceRegistry()
        val book = SourceBookId(Identifier("fixture", "a"), "same")
        val page = mockk<ExplorePageProvider.DefaultExplorePageProvider> {
            every { exploreExpandedPageDataSourceMap } returns mapOf("tag" to mockk {
                every { title } returns "A tag"
                every { filters } returns emptyList()
                every { getResultFlow() } returns flowOf(SearchResult.SingleBook("same"))
            })
        }
        val source = object : WebBookDataSource by EmptyWebDataSource {
            override val id = book.sourceId
            override val explorePageProvider = page
        }
        registry.register(source, SourceMetadata(WebDataSourceItem(book.sourceId, "A", "fixture"), emptySet()))
        val provider = mockk<WebBookDataSourceProvider>()
        val shelves = mockk<BookshelfRepository> { every { getAllBookshelfBookIdsFlow() } returns flowOf(emptyList()) }
        val text = mockk<TextProcessingRepository> { every { processText(any()) } answers { firstArg<() -> String>()() } }
        val books = mockk<BookRepository> { every { getBookInformationFlow(book, any()) } returns emptyFlow() }
        val store = ViewModelStore()
        val model = ExpandedPageViewModel(registry, provider, shelves, text, books)
        store.put("page", model)
        try {
            model.init("tag", book.storageKey)
            withTimeout(5000) {
                while (model.uiState.bookList.isEmpty()) { main.scheduler.runCurrent(); delay(1) }
            }
            assertEquals(book.storageKey, model.uiState.bookList.single().first)
            assertFalse(model.exploreUiState.isOffLine)
            registry.unregister(book.sourceId)
            model.refresh()
            withTimeout(5000) {
                while (!model.exploreUiState.isOffLine) { main.scheduler.runCurrent(); delay(1) }
            }
            verify(exactly = 0) { provider.value }
        } finally {
            store.clear(); registry.unregister(book.sourceId); Dispatchers.resetMain()
        }
    }
}
