package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import hnovel.execution.ExecutionAuthority
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryScroll
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
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }
    private fun model(saved: SavedStateHandle = SavedStateHandle()) = CategoriesViewModel(registry, accounts, saved).also {
        stores += ViewModelStore().apply { put("categories", it) }
        it.setActive(true)
    }
    private fun add(name: String, provider: DiscoveryProvider, builtIn: Boolean = false): Identifier {
        val id = Identifier("fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val discoveryProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"),
            if (provider.hasCategories) setOf(SourceCapability.Categories) else emptySet(), builtIn))
        return id
    }
    private open class Categories : DiscoveryProvider {
        override val hasCategories = true
        var calls = 0
        override suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> {
            calls++
            return Ok(listOf(DiscoveryCategory("same", "Same category", "tag")))
        }
    }

    @Test fun stableTabsAreLazyAndRetainVisitedContentAndScroll() = runTest(dispatcher) {
        val zProvider = Categories()
        val aProvider = Categories()
        val a = add("a", aProvider)
        val z = add("z", zProvider, builtIn = true)
        add("no-categories", object : DiscoveryProvider {})
        val model = model()
        advanceUntilIdle()
        assertEquals(listOf(z, a), model.state.value.sources.map { it.metadata.id })
        assertEquals(1, zProvider.calls)
        assertEquals(0, aProvider.calls)
        model.scroll(z, DiscoveryScroll(2, 19))
        model.select(a)
        advanceUntilIdle()
        model.select(z)
        advanceUntilIdle()
        assertEquals(1, zProvider.calls)
        assertEquals(1, aProvider.calls)
        assertEquals(DiscoveryScroll(2, 19), model.state.value.content[z]!!.scroll)
        model.setActive(false)
        model.setActive(true)
        advanceUntilIdle()
        assertEquals(1, zProvider.calls)
    }

    @Test fun initialInventoryLoadingEndsEvenWhenThereAreNoSources() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.state.value.loadingSources)
        advanceUntilIdle()
        assertFalse(model.state.value.loadingSources)
        assertTrue(model.state.value.sources.isEmpty())
        val id = add("later", Categories())
        advanceUntilIdle()
        assertEquals(id, model.state.value.selected)
        assertTrue(model.state.value.content[id]!!.loaded)
    }

    @Test fun cancelledLateACompletionCannotOverwriteB() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val a = add("a", object : Categories() {
            override suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> = withContext(NonCancellable) {
                started.complete(Unit)
                finish.await()
                Ok(listOf(DiscoveryCategory("late", "A late", "a")))
            }
        })
        val b = add("b", Categories())
        val model = model()
        runCurrent()
        assertTrue(started.isCompleted)
        model.select(b)
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(b, model.state.value.selected)
        assertEquals("Same category", model.state.value.content[b]!!.categories.single().title)
        assertFalse(model.state.value.content[a]!!.loaded)
    }

    @Test fun sourceQualifiedRoutesSurviveSerializationAndSameTitlesNeverMerge() = runTest(dispatcher) {
        val a = add("a", Categories())
        val b = add("b", Categories())
        val model = model()
        advanceUntilIdle()
        val first = model.result(model.state.value.content[a]!!.categories.single())!!
        model.select(b)
        advanceUntilIdle()
        val second = model.result(model.state.value.content[b]!!.categories.single())!!
        assertNotEquals(first.sourceId, second.sourceId)
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(first.title, second.title)
        assertEquals(second, Json.decodeFromString<Route.Main.DiscoveryResults>(Json.encodeToString(second)))
        assertNull(model.result(model.state.value.content[a]!!.categories.single()))
    }

    @Test fun replacementWithIdenticalMetadataRefreshesOnlyItsOwnPage() = runTest(dispatcher) {
        val aProvider = Categories()
        val a = add("a", aProvider)
        val bProvider = Categories()
        val b = add("b", bProvider)
        val model = model()
        advanceUntilIdle()
        model.select(b)
        advanceUntilIdle()
        registry.unregister(a)
        val replacement = Categories()
        add("a", replacement)
        advanceUntilIdle()
        assertEquals(1, bProvider.calls)
        assertEquals(0, replacement.calls)
        model.select(a)
        advanceUntilIdle()
        assertEquals(1, replacement.calls)
        accounts.begin(a)
        advanceUntilIdle()
        assertEquals(2, replacement.calls)
        assertEquals(1, bProvider.calls)
    }

    @Test fun processRestoreLoadsOnlySavedSourceAndRemovalHasDeterministicFallback() = runTest(dispatcher) {
        val aProvider = Categories()
        val a = add("a", aProvider)
        val bProvider = Categories()
        val b = add("b", bProvider)
        val saved = SavedStateHandle(mapOf("category.namespace" to b.namespace, "category.source" to b.id))
        val model = model(saved)
        advanceUntilIdle()
        assertEquals(b, model.state.value.selected)
        assertEquals(0, aProvider.calls)
        registry.unregister(b)
        advanceUntilIdle()
        assertEquals(a, model.state.value.selected)
        assertEquals(1, aProvider.calls)
        assertEquals(a.id, saved.get<String>("category.source"))
        registry.unregister(a)
        advanceUntilIdle()
        assertTrue(model.state.value.sources.isEmpty())
        assertNull(model.state.value.selected)
    }

    @Test fun failureIsSourceLocalAndExplicitRetryCanRecover() = runTest(dispatcher) {
        var failure = true
        val a = add("a", object : Categories() {
            override suspend fun categories() = if (failure) Err(DiscoveryError.AuthenticationRequired) else super.categories()
        })
        val b = add("b", Categories())
        val model = model()
        advanceUntilIdle()
        assertEquals(DiscoveryError.AuthenticationRequired, model.state.value.content[a]!!.error)
        model.select(b)
        advanceUntilIdle()
        assertNull(model.state.value.content[b]!!.error)
        model.select(a)
        failure = false
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.content[a]!!.loaded)
        assertNull(model.state.value.content[a]!!.error)
    }
}
