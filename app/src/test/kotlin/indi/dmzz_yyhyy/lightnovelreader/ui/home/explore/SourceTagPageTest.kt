package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import hnovel.execution.ExecutionAuthority
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryResultsViewModel
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SourceTagPageTest {
    @Test fun tagTargetAndResultSessionStayWithBookSourceAfterAnotherSourceIsOpenedOrOwnerRemoved() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val authority = ExecutionAuthority()
        val registry = WebSourceRegistry(dispatcher, authority)
        val stores = mutableListOf<ViewModelStore>()
        val book = SourceBookId(Identifier("fixture", "a"), "same")
        val b = Identifier("fixture", "b")
        for (id in listOf(book.sourceId, b)) registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override fun bookTagPage(tag: String) = "tag:$tag"
            override val discoveryProvider = object : DiscoveryProvider {
                override val hasCategories = true
                override suspend fun categories() = Ok(listOf(DiscoveryCategory("tag", "Tag", "tag:fantasy")))
                override suspend fun page(request: DiscoveryRequest) = Ok(DiscoveryPage(listOf(DiscoveryBook("same", id.id))))
            }
        }, SourceMetadata(WebDataSourceItem(id, id.id, "fixture"), setOf(SourceCapability.Categories)))
        try {
            val repository = BookRepository(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), registry)
            val target = repository.bookTagPage(book, "fantasy").get()!!
            registry.resolve(b)
            val route = Route.Main.DiscoveryResults(target.sourceId.namespace, target.sourceId.id, target.target, "Tag", "tag-session")
            val model = DiscoveryResultsViewModel(registry, SourceSessionManager(authority), SavedStateHandle(), route)
            stores += ViewModelStore().apply { put("page", model) }
            model.setActive(true)
            advanceUntilIdle()
            assertEquals(book, model.state.value.books.single().id)
            assertEquals("a", model.state.value.books.single().title)
            registry.unregister(book.sourceId)
            advanceUntilIdle()
            assertEquals(DiscoveryError.Unavailable, model.state.value.error)
            assertTrue(model.state.value.books.isEmpty())
            model.refresh()
            advanceUntilIdle()
            assertEquals(DiscoveryError.Unavailable, model.state.value.error)
            assertTrue(repository.bookTagPage(book, "fantasy").isErr)
        } finally {
            stores.forEach { it.clear() }
            registry.sources.value.forEach { registry.unregister(it.metadata.id) }
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }
}
