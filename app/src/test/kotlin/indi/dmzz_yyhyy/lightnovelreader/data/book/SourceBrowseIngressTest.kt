package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.app.Application
import com.github.michaelbull.result.*
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceBrowseIngressTest {
    private val type = SearchType("keyword", LocalString("Keyword"), LocalString("Search"))
    private fun add(registry: WebSourceRegistry, name: String, capabilities: Set<SourceCapability> = setOf(SourceCapability.Search)): Identifier {
        val id = Identifier("fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val searchProvider = object : SearchProvider {
                override val searchTypes = listOf(type)
                override fun search(searchType: SearchType, keyword: String) = flowOf(
                    SearchResult.SingleBook("same"), SearchResult.MultipleBook("same"))
                override fun getSearchSuggestions(history: List<String>, keyword: String) = listOf(name) + history
            }
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"), capabilities))
        return id
    }

    @Test fun capturedSessionsQualifyBothResultKindsAndSuggestionsNeverUseAnotherSource() = runTest {
        val registry = WebSourceRegistry(StandardTestDispatcher(testScheduler), hnovel.execution.ExecutionAuthority())
        val a = add(registry, "a")
        val b = add(registry, "b")
        try {
            val repository = ExploreRepository(registry)
            val first = repository.open(a).get()!!
            val second = repository.open(b).get()!!
            val results = first.search(type, "same title").toList()
            val key = SourceBookId(a, "same").storageKey
            assertEquals(key, (results[0] as SearchResult.SingleBook).bookId)
            assertEquals(key, (results[1] as SearchResult.MultipleBook).bookId)
            assertEquals(listOf("a", "shared history"), first.suggestions(listOf("shared history"), "same"))
            assertEquals(listOf("b"), second.suggestions(emptyList(), "same"))
        } finally { registry.unregister(a); registry.unregister(b) }
    }

    @Test fun missingOrUnsupportedSourceNeverFallsBackToAvailableSearchSource() = runTest {
        val registry = WebSourceRegistry(StandardTestDispatcher(testScheduler), hnovel.execution.ExecutionAuthority())
        val a = add(registry, "a")
        val unsupported = add(registry, "no-search", emptySet())
        try {
            val repository = ExploreRepository(registry)
            assertEquals(DiscoveryError.Unavailable, repository.open(Identifier("fixture", "missing")).getError()!!.error)
            assertEquals(DiscoveryError.Unsupported, repository.open(unsupported).getError()!!.error)
        } finally { registry.unregister(a); registry.unregister(unsupported) }
    }
}
