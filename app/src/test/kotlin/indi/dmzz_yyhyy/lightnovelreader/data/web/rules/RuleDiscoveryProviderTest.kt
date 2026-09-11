package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import com.github.michaelbull.result.*
import hnovel.content.RuleSourceFixture
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleDiscoveryProviderTest {
    @get:Rule val directory = TemporaryFolder()
    private fun definition(raw: JsonObject) = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive("[{\"title\":\"Sort\",\"type\":\"select\",\"chars\":[\"new\",\"popular\"]},{\"title\":\"Books\",\"url\":\"/search?sort={{infoMap.Sort}}&page={{page}}\"}]"),
        "ruleExplore" to raw.getValue("ruleSearch")))

    @Test fun productionAdapterBindsResultsAndKeepsRetryAndFiltersInTheOwningPage() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rule = fixture.source(customize = ::definition)
            val id = Identifier("rules", rule.definition.sourceId)
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rule), SourceMetadata(WebDataSourceItem(id, "Fixture", "Tests"),
                setOf(SourceCapability.Categories, SourceCapability.Explore)))
            try {
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val a = runtime.discovery!!.forSession("a", mapOf("Sort" to "new"))
                val b = runtime.discovery!!.forSession("b", mapOf("Sort" to "popular"))
                val first = a.catalog().get()!!
                val second = b.catalog().get()!!
                assertEquals(0, fixture.documents.get())
                val aPage = a.open(first.categories.single().target)
                val bPage = b.open(second.categories.single().target)
                aPage.reset(first.values); bPage.reset(second.values)
                val books = aPage.loadMore().get()!!.books
                assertEquals(id, books.single().id.sourceId)
                assertEquals("/search?sort=new&page=1", fixture.server.takeRequest().path)
                bPage.loadMore()
                assertEquals("/search?sort=popular&page=1", fixture.server.takeRequest().path)
                fixture.status = 401
                assertEquals(Err(DiscoveryError.AuthenticationRequired), aPage.loadMore())
                assertEquals("/search?sort=new&page=2", fixture.server.takeRequest().path)
                fixture.status = 200
                val repeated = aPage.loadMore().get()!!
                assertEquals("/search?sort=new&page=2", fixture.server.takeRequest().path)
                assertEquals(books, repeated.books)
                assertNull(repeated.nextCursor)
                val requests = fixture.documents.get()
                aPage.loadMore()
                assertEquals(requests, fixture.documents.get())
                assertEquals("popular", second.values["Sort"])
            } finally { registry.unregister(id) }
        }
    }

    @Test fun feedUsesSourceOrderAndOnlyLoadsTheFirstPreview() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rule = fixture.source { raw -> definition(raw).let {
                JsonObject(it + ("exploreUrl" to JsonPrimitive("New::/search&&Completed::/search?complete=1&&Popular::/search?hot=1")))
            } }
            val provider = RuleDiscoveryProvider(rule)
            val feed = provider.feed().get()!!
            assertEquals(listOf("New", "Completed", "Popular"), feed.map { it.title })
            assertEquals(listOf(1, 0, 0), feed.map { it.books.size })
            assertTrue(feed.all { it.more != null })
            assertTrue(provider.hasInteractions)
            assertEquals(provider.catalog().get()!!.categories.map { it.id }, feed.map { it.categoryId })
            assertEquals(1, fixture.documents.get())
        }
    }

    @Test fun pageLimitKeepsTheLastAllowedBooksAndReportsTheNextAttemptWithoutFetching() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val provider = RuleDiscoveryProvider(fixture.source(customize = ::definition))
            val last = provider.page(DiscoveryRequest("/search", "64")).get()!!
            assertEquals(1, last.books.size)
            assertEquals("65", last.nextCursor)
            assertEquals(Err(DiscoveryError.InvalidRules), provider.page(DiscoveryRequest("/search", last.nextCursor)))
            assertEquals("ruleExplore.page", provider.failureField)
            assertEquals(1, fixture.documents.get())
        }
    }

    @Test fun unknownDialectReportsTheOriginalFieldThroughTheHostAdapter() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { raw -> JsonObject(definition(raw) +
                ("exploreUrl" to JsonPrimitive("[{\"title\":\"Unsupported\",\"type\":\"nativeView\"}]"))) }
            val provider = RuleDiscoveryProvider(source)
            assertEquals(Err(DiscoveryError.InvalidRules), provider.catalog())
            assertEquals("exploreUrl[0].type", provider.failureField)
        }
    }

    @Test fun formKeysIgnoreViewNamesAndConfigurationRefreshDoesNotNavigate() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to JsonPrimitive("""[
                {"title":"Query","type":"text","default":"start","viewName":"'Search '+infoMap.Query"},
                {"id":"configure","title":"Configure","type":"button","action":"java.upConfig({category:'new'});"},
                {"id":"settings","title":"Settings","type":"button","action":"java.upConfig();"},
                {"id":"search","title":"Search","type":"button","action":"java.searchBook(infoMap.Query);"}
            ]"""))) }
            val provider = RuleDiscoveryProvider(source)
            val catalog = provider.catalog().get()!!
            assertEquals(DiscoveryFilter.Text("Query", "Search start", "start"), catalog.filters.single())
            assertEquals(mapOf("Query" to "start"), catalog.values)
            val configured = provider.interact("configure").get()!!
            assertTrue(configured.refresh)
            assertTrue(configured.actions.isEmpty())
            val settings = provider.interact("settings").get()!!
            assertEquals(listOf(DiscoveryAction.Settings), settings.actions)
            assertFalse(settings.refresh)
            val search = provider.interact("search").get()!!.actions.single() as DiscoveryAction.Results
            assertEquals(DISCOVERY_SEARCH_PREFIX + "start", search.target)
            assertTrue(provider.filters(search.target).isEmpty())
        }
    }

    @Test fun importedCapabilitiesAndAccountReplacementNeverRemoveTheSelectedTab() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val authority = ExecutionAuthority()
            val accounts = SourceSessionManager(authority)
            val registry = WebSourceRegistry(authority)
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir() = directory.root
            }
            val imported = ImportedRuleSources(context, registry, authority, accounts, fixture.runner)
            try {
                val preview = imported.importer.preview(definition(fixture.raw()).toString())
                val committed = imported.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = imported.activate(committed.items.single().reference!!,
                    listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))
                val initial = registry.sources.value.single()
                assertTrue(initial.metadata.capabilities.containsAll(setOf(SourceCapability.Explore, SourceCapability.Categories, SourceCapability.Search)))
                val snapshots = mutableListOf<List<SourceListing>>()
                val observer = launch(Dispatchers.Unconfined) { registry.sources.collect { snapshots += it } }
                imported.rotateAccount(id)
                observer.cancelAndJoin()
                assertTrue(snapshots.isNotEmpty())
                assertTrue(snapshots.all { list -> list.any { it.metadata.id == id && SourceCapability.Categories in it.metadata.capabilities } })
                assertTrue(registry.sources.value.single().generation > initial.generation)
                assertEquals(1L, registry.sources.value.single().metadata.accountGeneration)
            } finally { imported.stop() }
        }
    }
}
