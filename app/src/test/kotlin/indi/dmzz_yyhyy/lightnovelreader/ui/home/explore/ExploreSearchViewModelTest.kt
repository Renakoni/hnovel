package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Ok
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import hnovel.execution.ExecutionAuthority
import indi.dmzz_yyhyy.lightnovelreader.data.book.*
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.explore.*
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.Route
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ExploreSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val stores = mutableListOf<ViewModelStore>()
    private val historyText = MutableStateFlow<String?>(null)
    private val dao = mockk<UserDataDao> {
        every { getFlow(any()) } returns historyText
        coEvery { this@mockk.get(any<String>()) } answers { historyText.value }
        coEvery { insert(any(), any(), any(), any()) } coAnswers { historyText.value = arg(3) }
    }
    private val users = UserDataRepository(dao)
    private val shelves = mockk<BookshelfRepository> { every { getAllBookshelfBookIdsFlow() } returns flowOf(emptyList()) }
    private val books = mockk<BookRepository> { every { getBookInformationFlow(any<String>(), any()) } returns emptyFlow() }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }
    private class Search : SearchProvider {
        override val searchTypes = listOf("title", "author").map { SearchType(it, LocalString(it), LocalString(it)) }
        val requests = mutableListOf<Pair<String, String>>()
        var results: (String) -> Flow<SearchResult> = { flowOf(SearchResult.MultipleBook("same"), SearchResult.End()) }
        override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> {
            requests += searchType.type to keyword
            return results(keyword)
        }
        override fun getSearchSuggestions(history: List<String>, keyword: String) = history.filter { it.startsWith(keyword) }
    }
    private fun add(name: String, search: SearchProvider = Search(), capabilities: Set<SourceCapability> = setOf(SourceCapability.Search)): Identifier {
        val id = Identifier("fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val searchProvider = search
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"), capabilities))
        return id
    }
    private fun model(id: Identifier, saved: SavedStateHandle = SavedStateHandle(), repository: ExploreRepository = ExploreRepository(registry)) =
        ExploreSearchViewModel(repository, shelves, books, users, registry, accounts, saved,
            Route.Main.Explore.Search(id.namespace, id.id), dispatcher).also {
            stores += ViewModelStore().apply { put("search", it) }
            it.setActive(true)
        }

    @Test fun sharedHistoryNeverChangesSourceAndCompletedSearchSurvivesShortNavigation() = runTest(dispatcher) {
        val aProvider = Search()
        val bProvider = Search()
        val a = add("a", aProvider)
        val b = add("b", bProvider)
        val first = model(a)
        advanceUntilIdle()
        assertTrue(bProvider.requests.isEmpty())
        first.search("shared title")
        advanceUntilIdle()
        first.setActive(false)
        first.setActive(true)
        first.setActive(false)
        first.setActive(true)
        advanceUntilIdle()
        assertEquals(1, aProvider.requests.size)
        verify(exactly = 1) { dao.getFlow(any()) }
        verify(exactly = 1) { shelves.getAllBookshelfBookIdsFlow() }
        val second = model(b)
        advanceUntilIdle()
        second.search(second.uiState.historyList.single())
        advanceUntilIdle()
        assertEquals("shared title", bProvider.requests.single().second)
        assertEquals(SourceBookId(a, "same").storageKey, first.uiState.searchResult.single().first)
        assertEquals(SourceBookId(b, "same").storageKey, second.uiState.searchResult.single().first)
        assertEquals(a, first.sourceId)
        assertEquals(b, second.sourceId)
    }

    @Test fun lateSearchCannotReplaceNewResultsOrNavigateToOldSingleBook() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val provider = Search().apply {
            results = { keyword -> if (keyword == "old") flow {
                withContext(NonCancellable) { started.complete(Unit); finish.await() }
                emit(SearchResult.SingleBook("old"))
            } else flowOf(SearchResult.MultipleBook("new"), SearchResult.End()) }
        }
        val id = add("a", provider)
        val model = model(id)
        val commands = mutableListOf<SearchNavigation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.navigation.collect { commands += it } }
        advanceUntilIdle()
        model.search("old")
        runCurrent()
        assertTrue(started.isCompleted)
        model.search("new")
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(SourceBookId(id, "new").storageKey, model.uiState.searchResult.single().first)
        assertTrue(commands.isEmpty())
        assertTrue(model.uiState.isLoadingComplete)
    }

    @Test fun queuedSingleBookCommandExpiresWhenLeavingEvenIfCollectedAfterReturn() = runTest(dispatcher) {
        val provider = Search().apply { results = { flowOf(SearchResult.SingleBook("same")) } }
        val id = add("a", provider)
        val model = model(id)
        advanceUntilIdle()
        model.search("direct")
        advanceUntilIdle()
        model.setActive(false)
        model.setActive(true)
        advanceUntilIdle()
        val queued = model.navigation.first()
        assertEquals(SourceBookId(id, "same").storageKey, queued.bookId)
        assertFalse(model.accepts(queued))
        assertEquals(1, provider.requests.size)
    }

    @Test fun delayedSuggestionCannotOverwriteNewQueryOrType() = runTest(dispatcher) {
        val id = add("a")
        val old = CompletableDeferred<List<String>>()
        val session = mockk<SourceSearch> {
            every { types } returns Search().searchTypes
            coEvery { suggestions(any(), "old") } coAnswers { withContext(NonCancellable) { old.await() } }
            coEvery { suggestions(any(), "new") } returns listOf("new suggestion")
        }
        val repository = mockk<ExploreRepository> { coEvery { open(id) } returns Ok(session) }
        val model = model(id, repository = repository)
        advanceUntilIdle()
        model.updateSuggestions("old")
        runCurrent()
        model.updateSuggestions("new")
        runCurrent()
        old.complete(listOf("old suggestion"))
        advanceUntilIdle()
        assertEquals(listOf("new suggestion"), model.uiState.suggestions)
        model.changeSearchType("author")
        advanceUntilIdle()
        assertEquals("author", model.uiState.searchType)
        assertEquals(listOf("new suggestion"), model.uiState.suggestions)
    }

    @Test fun leavingCancelsPartialSearchAndReturnReloadsOnlyThatSource() = runTest(dispatcher) {
        var calls = 0
        var cancelled = 0
        val provider = Search().apply { results = { flow {
            calls++
            emit(SearchResult.MultipleBook("same"))
            if (calls == 1) try { awaitCancellation() } finally { cancelled++ }
            emit(SearchResult.End())
        } } }
        val id = add("a", provider)
        val model = model(id)
        advanceUntilIdle()
        model.search("title")
        runCurrent()
        assertFalse(model.uiState.isLoadingComplete)
        model.setActive(false)
        runCurrent()
        assertEquals(1, cancelled)
        model.setActive(true)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertTrue(model.uiState.isLoadingComplete)
        assertEquals(1, model.uiState.searchResult.size)
    }

    @Test fun accountOrRegistrationChangesReloadOnlyMatchingSourceAndInvalidateOldCommands() = runTest(dispatcher) {
        val aProvider = Search()
        val bProvider = Search()
        val a = add("a", aProvider)
        val b = add("b", bProvider)
        val first = model(a)
        val second = model(b)
        advanceUntilIdle()
        first.search("title")
        second.search("title")
        advanceUntilIdle()
        accounts.begin(a)
        advanceUntilIdle()
        assertEquals(2, aProvider.requests.size)
        assertEquals(1, bProvider.requests.size)
        registry.unregister(a)
        val replacement = Search()
        add("a", replacement)
        advanceUntilIdle()
        assertEquals(1, replacement.requests.size)
        assertEquals(1, bProvider.requests.size)
        registry.unregister(a)
        advanceUntilIdle()
        assertEquals(DiscoveryError.Unavailable, first.uiState.failure!!.error)
        assertTrue(first.uiState.searchResult.isEmpty())
        assertEquals(1, second.uiState.searchResult.size)
    }

    @Test fun processRecreationRestoresOnlySourceQueryAndTypeThenLoadsFreshContent() = runTest(dispatcher) {
        val provider = Search()
        val id = add("a", provider)
        add("b")
        val saved = SavedStateHandle()
        val first = model(id, saved)
        advanceUntilIdle()
        first.changeSearchType("author")
        first.search("writer")
        advanceUntilIdle()
        first.setActive(false)
        val restored = model(id, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals(id, restored.sourceId)
        assertEquals("author", restored.uiState.searchType)
        assertEquals("writer", restored.uiState.query)
        assertEquals(listOf("author" to "writer", "author" to "writer"), provider.requests)
        assertTrue(saved.keys().all { it in setOf("search.query", "search.type", "search.submitted", "search.expanded") })
    }

    @Test fun typedSourceFailuresStayLocalAndCanBeRetriedWithoutRawExceptionMessages() = runTest(dispatcher) {
        val provider = Search()
        val id = add("a", provider)
        val model = model(id)
        advanceUntilIdle()
        val expected = listOf(
            ContentError.LoginRequired to DiscoveryError.AuthenticationRequired,
            ContentError.PermissionDenied to DiscoveryError.PermissionDenied,
            ContentError.InvalidRule to DiscoveryError.InvalidRules,
            ContentError.Network to DiscoveryError.Network,
            ContentError.MissingCapability to DiscoveryError.Unsupported)
        for ((code, error) in expected) {
            provider.results = { flowOf(SearchResult.Error(SourceContentException(code, "ruleSearch.bookList"))) }
            model.search(code.name)
            advanceUntilIdle()
            assertEquals(error, model.uiState.failure!!.error)
            assertEquals("ruleSearch.bookList", model.uiState.failure!!.field)
            assertFalse(model.uiState.isLoading)
        }
        provider.results = { flowOf(SearchResult.MultipleBook("same"), SearchResult.End()) }
        model.retry()
        advanceUntilIdle()
        assertNull(model.uiState.failure)
        assertEquals(1, model.uiState.searchResult.size)
    }

    @Test fun missingOrSearchUnsupportedRouteDoesNotSelectAnAvailableSource() = runTest(dispatcher) {
        val provider = Search()
        add("available", provider)
        val unsupported = add("unsupported", capabilities = emptySet())
        val missing = model(Identifier("fixture", "missing"))
        val disabled = model(unsupported)
        advanceUntilIdle()
        assertEquals(DiscoveryError.Unavailable, missing.uiState.failure!!.error)
        assertEquals(DiscoveryError.Unsupported, disabled.uiState.failure!!.error)
        missing.search("shared history")
        missing.retry()
        advanceUntilIdle()
        assertEquals(DiscoveryError.Unavailable, missing.uiState.failure!!.error)
        assertFalse(missing.uiState.isLoading)
        assertTrue(provider.requests.isEmpty())
    }
}
