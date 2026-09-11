package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.*
import hnovel.execution.ExecutionAuthority
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.home.ExploreHomeViewModel
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
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
class ExploreHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val stores = mutableListOf<ViewModelStore>()
    private val text = mockk<TextProcessingRepository> {
        every { processExploreBooksRow(any()) } answers { firstArg() }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }
    private fun model(saved: SavedStateHandle = SavedStateHandle()) =
        ExploreHomeViewModel(registry, accounts, saved, text).also {
            stores += ViewModelStore().apply { put("explore", it) }
            it.setActive(true)
        }
    private fun add(name: String, provider: DiscoveryProvider, builtIn: Boolean = false,
        capabilities: Set<SourceCapability> = setOf(SourceCapability.Explore, SourceCapability.Categories, SourceCapability.Search)): Identifier {
        val id = Identifier("fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val discoveryProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"), capabilities, builtIn))
        return id
    }
    private open class Feed : DiscoveryProvider {
        override val hasFeed = true
        override val hasCategories = true
        var feeds = 0
        var catalogs = 0
        var failure: DiscoveryError? = null
        override suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> {
            feeds++
            return failure?.let { Err(it) } ?: Ok(listOf(DiscoverySection("same", "Same list",
                listOf(DiscoveryBook("same", "Book")), "all")))
        }
        override suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> {
            catalogs++
            return Ok(listOf(DiscoveryCategory("all", "All", "all")))
        }
    }

    @Test fun realTabsFilterCapabilitiesLoadOnlySelectedAndKeepVisitedFeedAndScroll() = runTest(dispatcher) {
        val aFeed = Feed()
        val zFeed = Feed()
        val a = add("a", aFeed)
        val z = add("z", zFeed, builtIn = true)
        add("search-only", Feed(), capabilities = setOf(SourceCapability.Search))
        val model = model()
        advanceUntilIdle()
        assertEquals(listOf(z, a), model.state.value.sources.map { it.metadata.id })
        assertEquals(0, aFeed.feeds)
        assertEquals(1, zFeed.feeds)
        assertEquals(0, zFeed.catalogs) // Native feed does not preload the category directory.
        model.scroll(z, DiscoveryScroll(3, 17))
        model.select(a)
        advanceUntilIdle()
        model.select(z)
        model.setActive(false)
        model.setActive(true)
        advanceUntilIdle()
        assertEquals(1, zFeed.feeds)
        assertEquals(1, aFeed.feeds)
        assertEquals(DiscoveryScroll(3, 17), model.state.value.content[z]!!.scroll)
    }

    @Test fun failuresAndRetryRemainLocalAndFailedRefreshRetainsVisibleBooks() = runTest(dispatcher) {
        val feed = Feed()
        val a = add("a", feed)
        val b = add("b", Feed())
        val model = model()
        advanceUntilIdle()
        val shown = model.state.value.content[a]!!.sections
        feed.failure = DiscoveryError.AuthenticationRequired
        model.refresh()
        advanceUntilIdle()
        assertEquals(shown, model.state.value.content[a]!!.sections)
        assertEquals(DiscoveryError.AuthenticationRequired, model.state.value.content[a]!!.error)
        model.select(b)
        advanceUntilIdle()
        assertNull(model.state.value.content[b]!!.error)
        model.select(a)
        advanceUntilIdle()
        assertEquals(2, feed.feeds)
        feed.failure = null
        model.refresh()
        advanceUntilIdle()
        assertNull(model.state.value.content[a]!!.error)
        assertEquals(3, feed.feeds)
    }

    @Test fun lateFeedCompletionCannotOverwriteNewSourceOrRefresh() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val a = add("a", object : Feed() {
            override suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> = withContext(NonCancellable) {
                started.complete(Unit)
                finish.await()
                Ok(listOf(DiscoverySection("late", "Late A", emptyList())))
            }
        })
        val b = add("b", Feed())
        val model = model()
        runCurrent()
        assertTrue(started.isCompleted)
        model.select(b)
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(b, model.state.value.selected)
        assertEquals("Same list", model.state.value.content[b]!!.sections.single().title)
        assertFalse(model.state.value.content[a]!!.loaded)
    }

    @Test fun moreSearchAndCategoryRoutesKeepSourceAndUseIndependentResultSessions() = runTest(dispatcher) {
        val a = add("a", Feed())
        val b = add("b", Feed())
        val model = model()
        advanceUntilIdle()
        val firstSection = model.state.value.content[a]!!.sections.single()
        val first = model.more(firstSection)!!
        val search = model.search()!!
        val category = model.categories()!!
        model.select(b)
        advanceUntilIdle()
        val second = model.more(model.state.value.content[b]!!.sections.single())!!
        assertEquals(a, firstSection.books.single().id.sourceId)
        assertEquals(a.id, search.sourceId)
        assertEquals(a.id, category.sourceId)
        assertEquals(b.id, second.sourceId)
        assertNotEquals(first.sessionId, second.sessionId)
        assertNull(model.more(firstSection))
        assertEquals(search, Json.decodeFromString<Route.Main.Explore.Search>(Json.encodeToString(search)))
        assertEquals(category, Json.decodeFromString<Route.Main.Categories>(Json.encodeToString(category)))
        assertEquals(first, Json.decodeFromString<Route.Main.DiscoveryResults>(Json.encodeToString(first)))
    }

    @Test fun absentSearchAndCategoriesCapabilitiesDoNotOfferRoutes() = runTest(dispatcher) {
        add("feed", Feed(), capabilities = setOf(SourceCapability.Explore))
        val model = model()
        advanceUntilIdle()
        assertNull(model.search())
        assertNull(model.categories())
    }

    @Test fun sourceRegistrationAndAccountChangesInvalidateOnlyTheirOwnContent() = runTest(dispatcher) {
        val a = add("a", Feed())
        val bFeed = Feed()
        val b = add("b", bFeed)
        val model = model()
        advanceUntilIdle()
        model.select(b)
        advanceUntilIdle()
        model.scroll(b, DiscoveryScroll(2, 11))
        val oldReset = model.state.value.content[a]!!.resetId
        registry.unregister(a)
        val replaced = Feed()
        add("a", replaced)
        advanceUntilIdle()
        assertEquals(1, bFeed.feeds)
        assertEquals(0, replaced.feeds)
        assertEquals(DiscoveryScroll(2, 11), model.state.value.content[b]!!.scroll)
        model.select(a)
        advanceUntilIdle()
        assertNotEquals(oldReset, model.state.value.content[a]!!.resetId)
        accounts.begin(a)
        advanceUntilIdle()
        assertEquals(2, replaced.feeds)
        assertEquals(1, bFeed.feeds)
    }

    @Test fun restoreLoadsSavedSourceOnlyAndRemovalFallsBackWithoutRestoringCachedData() = runTest(dispatcher) {
        val aFeed = Feed()
        val a = add("a", aFeed)
        val b = add("b", Feed())
        val saved = SavedStateHandle(mapOf("explore.namespace" to b.namespace, "explore.source" to b.id))
        val model = model(saved)
        advanceUntilIdle()
        assertEquals(b, model.state.value.selected)
        assertEquals(0, aFeed.feeds)
        assertTrue(saved.keys().all { it in setOf("explore.namespace", "explore.source", "explore.session") })
        registry.unregister(b)
        advanceUntilIdle()
        assertEquals(a, model.state.value.selected)
        assertEquals(1, aFeed.feeds)
        assertEquals(a.id, saved.get<String>("explore.source"))
        registry.unregister(a)
        advanceUntilIdle()
        assertNull(model.state.value.selected)
        assertTrue(model.state.value.content.isEmpty())
    }

    @Test fun interactiveFeedUsesSameDraftAndCarriesCategoryIdentityAndValuesToMore() = runTest(dispatcher) {
        var sort = "new"
        val provider = object : Feed() {
            override val hasInteractions = true
            fun snapshot() = DiscoveryCatalog(listOf(DiscoveryCategory("books", "Books", sort)),
                listOf(DiscoveryFilter.Text("Sort", "Sort", "new")), mapOf("Sort" to sort))
            override suspend fun catalog(refresh: Boolean): Result<DiscoveryCatalog, DiscoveryError> {
                catalogs++
                return Ok(snapshot())
            }
            override suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> {
                feeds++
                return Ok(listOf(DiscoverySection("books", "Books", emptyList(), sort, "books")))
            }
            override suspend fun interact(id: String, value: String?, longClick: Boolean): Result<DiscoveryUpdate, DiscoveryError> {
                sort = value!!
                return Ok(DiscoveryUpdate(snapshot()))
            }
        }
        val source = add("rules", provider)
        val model = model()
        advanceUntilIdle()
        assertEquals(1, provider.feeds)
        assertEquals(1, provider.catalogs)
        model.interact("Sort", "popular")
        advanceUntilIdle()
        val route = model.more(model.state.value.content[source]!!.sections.single())!!
        assertEquals("popular", route.target)
        assertEquals("books", route.categoryId)
        assertEquals(mapOf("Sort" to "popular"), Json.decodeFromString<Map<String, String>>(route.filtersJson))
        assertEquals(2, provider.feeds)
    }
}
