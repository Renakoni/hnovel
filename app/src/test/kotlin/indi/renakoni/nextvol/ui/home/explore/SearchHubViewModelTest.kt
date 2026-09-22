package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.*
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.explore.*
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.explore.search.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SearchHubViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val coordinator = SearchCoordinator(ExploreRepository(registry), dispatcher)
    private val stores = mutableListOf<ViewModelStore>()
    private val historyText = MutableStateFlow<String?>(null)
    private val dao = mockk<UserDataDao> {
        every { getFlow(any()) } returns historyText
        coEvery { this@mockk.get(any<String>()) } answers { historyText.value }
        coEvery { insert(any(), any(), any(), any()) } coAnswers { historyText.value = arg(3) }
    }
    private val books = mockk<BookRepository> {
        every { getBookInformationFlow(any<String>(), any()) } returns emptyFlow()
    }
    private val browsing = mockk<SourceBrowseSettings> { every { scope } returns MutableStateFlow(null) }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private open class Stream : SearchProvider {
        override val searchTypes = listOf(SearchType("keyword", LocalString("keyword"), LocalString("keyword")))
        var events: (String) -> Flow<SearchResult> = { flowOf(SearchResult.End()) }
        override fun search(searchType: SearchType, keyword: String) = events(keyword)
    }
    private class Paged : Stream(), PagedSearchProvider {
        val requests = mutableListOf<Pair<String, Int>>()
        var load: suspend (String, Int) -> SearchPage = { _, _ -> SearchPage(emptyList(), null) }
        override suspend fun searchPage(type: SearchType, keyword: String, page: Int): SearchPage {
            requests += keyword to page
            return load(keyword, page)
        }
    }
    private fun add(name: String, provider: SearchProvider = Paged(), category: SourceCategory? = null,
        capabilities: Set<SourceCapability> = setOf(SourceCapability.Search)): Identifier {
        val id = Identifier("search-fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val searchProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"), capabilities, category = category))
        return id
    }
    private fun model(saved: SavedStateHandle = SavedStateHandle()) = SearchHubViewModel(registry, coordinator, books,
        UserDataRepository(dao), accounts, saved, browsing, dispatcher).also {
        stores += ViewModelStore().apply { put("search", it) }
        it.setActive(true)
    }
    private fun item(id: String, title: String = id) = SearchResult.MultipleBook(id, BookInformation(id, title,
        author = "Author", description = "Description", publishingHouse = "", wordCount = WordCount(0),
        lastUpdated = LocalDateTime.MIN, isComplete = false))

    @Test fun thousandSourcesAreLazyAndRunWithAtMostFourRequests() = runTest(dispatcher) {
        var active = 0
        var peak = 0
        var calls = 0
        repeat(1000) { index -> add("source-$index", Paged().apply { load = { _, _ ->
            active++; calls++; peak = maxOf(peak, active)
            try { delay(10); SearchPage(emptyList(), null) } finally { active-- }
        } }) }
        val model = model()
        runCurrent()
        assertEquals(0, calls)
        model.search("title")
        runCurrent()
        assertEquals(4, calls)
        advanceUntilIdle()
        assertEquals(4, peak)
        assertEquals(1000, calls)
        assertEquals(1000, model.state.value.completed)
        assertFalse(model.state.value.searching)
        assertTrue(model.state.value.books.isEmpty())
    }

    @Test fun groupsRestrictRequestsAndAnEmptyGroupNeverFallsBackToAllSources() = runTest(dispatcher) {
        val adult = Paged()
        val general = Paged()
        val unsupported = Paged()
        add("adult", adult, SourceCategory.Adult)
        add("general", general, SourceCategory.General)
        add("unsupported", unsupported, SourceCategory.Adult, emptySet())
        val model = model()
        runCurrent()
        model.selectScope(SourceCategory.Adult)
        model.search("title")
        advanceUntilIdle()
        assertEquals(1, adult.requests.size)
        assertTrue(general.requests.isEmpty())
        assertTrue(unsupported.requests.isEmpty())
        model.selectScope(SourceCategory.Literature)
        advanceUntilIdle()
        assertTrue(model.state.value.scopedSources.isEmpty())
        assertEquals(1, adult.requests.size)
        assertTrue(general.requests.isEmpty())
    }

    @Test fun pageRequestsAreOnDemandAndSameTitleFromDifferentSourcesRetainsItsIdentity() = runTest(dispatcher) {
        val a = Paged().apply { load = { _, page -> when (page) {
            1 -> SearchPage((1..20).map { item("$it", "Title") }, 2)
            else -> SearchPage(listOf(item("20", "Title"), item("21", "Title")), null)
        } } }
        val b = Paged().apply { load = { _, _ -> SearchPage(listOf(item("1", "Title")), null) } }
        val aId = add("a", a)
        val bId = add("b", b)
        val model = model()
        runCurrent()
        model.search("Title")
        advanceUntilIdle()
        assertEquals(21, model.state.value.books.size)
        assertTrue(model.state.value.hasMore)
        assertEquals(listOf("Title" to 1), a.requests)
        assertTrue(model.state.value.books.any { it.id == SourceBookId(aId, "1").storageKey })
        assertTrue(model.state.value.books.any { it.id == SourceBookId(bId, "1").storageKey })
        model.loadMore()
        model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("Title" to 1, "Title" to 2), a.requests)
        assertEquals(1, b.requests.size)
        assertEquals(22, model.state.value.books.size)
        assertFalse(model.state.value.hasMore)
        assertTrue(model.state.value.books.first().information.first().isOk)
        verify(exactly = 0) { books.getBookInformationFlow(any<String>(), any()) }
    }

    @Test fun repeatedPageEndsPaginationAndMatchingBooksRankAheadOfUnrelatedTitles() = runTest(dispatcher) {
        val provider = Paged().apply { load = { _, page -> SearchPage(listOf(item("other", "Other"), item("match", "Title")), page + 1) } }
        add("a", provider)
        val model = model()
        runCurrent()
        model.search("Title")
        advanceUntilIdle()
        assertEquals("Title", model.state.value.books.first().preview!!.title)
        model.loadMore()
        advanceUntilIdle()
        assertEquals(2, model.state.value.books.size)
        assertFalse(model.state.value.hasMore)
    }

    @Test fun replacingQueryWaitsForOldRequestsAndNeverPublishesTheirLateResults() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val providers = List(8) { Paged().apply { load = { keyword, _ ->
            active++; peak = maxOf(peak, active)
            try {
                if (keyword == "old") withContext(NonCancellable) { release.await() }
                SearchPage(listOf(item(keyword)), null)
            } finally { active-- }
        } } }
        providers.forEachIndexed { i, provider -> add("$i", provider) }
        val model = model()
        runCurrent()
        model.search("old")
        runCurrent()
        model.search("new")
        runCurrent()
        assertTrue(providers.all { provider -> provider.requests.none { it.first == "new" } })
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, peak)
        assertEquals(8, model.state.value.books.size)
        assertTrue(model.state.value.books.all { it.preview?.title == "new" })
    }

    @Test fun timeoutsAndFailuresStayLocalAndRetryDoesNotRepeatSuccessfulSources() = runTest(dispatcher) {
        val good = Paged().apply { load = { _, _ -> SearchPage(listOf(item("good")), null) } }
        val slow = Paged().apply { load = { _, _ -> awaitCancellation() } }
        val denied = Paged().apply { load = { _, _ -> throw SourceRequestException(DiscoveryError.AuthenticationRequired) } }
        add("good", good); add("slow", slow); add("denied", denied)
        val model = model()
        runCurrent()
        model.search("query")
        runCurrent()
        assertEquals(1, model.state.value.books.size)
        assertTrue(model.state.value.searching)
        advanceUntilIdle()
        assertEquals(2, model.state.value.failures.size)
        assertTrue(model.state.value.failures.any { it.failure!!.error == DiscoveryError.AuthenticationRequired })
        slow.load = { _, _ -> SearchPage(listOf(item("slow")), null) }
        denied.load = { _, _ -> SearchPage(listOf(item("denied")), null) }
        model.retryFailures()
        advanceUntilIdle()
        assertTrue(model.state.value.failures.isEmpty())
        assertEquals(1, good.requests.size)
        assertEquals(3, model.state.value.books.size)
    }

    @Test fun legacyStreamsRetainPartialResultsAndStopAtTerminalEvents() = runTest(dispatcher) {
        var reachedAfterEnd = false
        add("legacy", Stream().apply { events = { flow {
            repeat(25) { emit(item("$it")) }
            emit(SearchResult.End())
            reachedAfterEnd = true
            awaitCancellation()
        } } })
        add("partial", Stream().apply { events = { flow {
            emit(item("first")); emit(SearchResult.Error(java.io.IOException())); emit(item("late"))
        } } })
        val model = model()
        runCurrent(); model.search("query"); advanceUntilIdle()
        assertEquals(26, model.state.value.books.size)
        assertEquals(1, model.state.value.failures.size)
        assertFalse(reachedAfterEnd)
        assertFalse(model.state.value.searching)
    }

    @Test fun stopAndNavigationPreserveResultsAndOnlyInterruptedSourcesResume() = runTest(dispatcher) {
        val good = Paged().apply { load = { _, _ -> SearchPage(listOf(item("good")), null) } }
        val slow = Paged().apply { load = { _, _ -> awaitCancellation() } }
        add("good", good); add("slow", slow)
        val model = model()
        runCurrent(); model.search("query"); runCurrent()
        model.stop(); runCurrent()
        assertEquals(1, model.state.value.books.size)
        model.setActive(false); model.setActive(true); runCurrent()
        assertEquals(1, slow.requests.size)
        slow.load = { _, _ -> SearchPage(listOf(item("slow")), null) }
        model.resume(); advanceUntilIdle()
        assertEquals(1, good.requests.size)
        assertEquals(2, slow.requests.size)
        model.setActive(false); model.setActive(true); advanceUntilIdle()
        assertEquals(2, slow.requests.size)
        assertEquals(2, model.state.value.books.size)
    }

    @Test fun registrationAndAccountChangesInvalidateOnlyAffectedSourceResults() = runTest(dispatcher) {
        val a = Paged().apply { load = { _, _ -> SearchPage(listOf(item("a")), null) } }
        val b = Paged().apply { load = { _, _ -> SearchPage(listOf(item("b")), null) } }
        val id = add("a", a); add("b", b)
        val model = model()
        runCurrent(); model.search("query"); advanceUntilIdle()
        accounts.begin(id); advanceUntilIdle()
        assertEquals(2, a.requests.size)
        assertEquals(1, b.requests.size)
        registry.unregister(id); advanceUntilIdle()
        assertEquals(1, model.state.value.books.size)
        assertEquals("b", model.state.value.books.single().preview!!.title)
    }

    @Test fun savedStateRestoresQueryAndScopeButReloadsContentAndHistoryKeepsNewestFirst() = runTest(dispatcher) {
        val provider = Paged()
        add("a", provider, SourceCategory.Adult)
        val saved = SavedStateHandle()
        val model = model(saved)
        runCurrent()
        model.selectScope(SourceCategory.Adult)
        model.search(" first "); advanceUntilIdle()
        model.search("second"); advanceUntilIdle()
        model.search("first"); advanceUntilIdle()
        assertEquals(listOf("first", "second"), model.state.value.history)
        model.setActive(false)
        val restored = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals("first", restored.state.value.query)
        assertEquals(SourceCategory.Adult, restored.state.value.scope)
        assertEquals(4, provider.requests.size)
        restored.deleteHistory("first"); advanceUntilIdle()
        assertEquals(listOf("second"), restored.state.value.history)
        restored.clearHistory(); advanceUntilIdle()
        assertTrue(restored.state.value.history.isEmpty())
    }

    @Test fun resultsAreBoundedAndTruncationIsExplicit() = runTest(dispatcher) {
        repeat(2) { source -> add("$source", Paged().apply { load = { _, _ ->
            SearchPage((1..600).map { item("$it") }, 2)
        } }) }
        val model = model()
        runCurrent(); model.search("query"); advanceUntilIdle()
        assertEquals(1000, model.state.value.books.size)
        assertTrue(model.state.value.limited)
        assertFalse(model.state.value.hasMore)
        assertEquals(2, model.state.value.completed)
    }
}
