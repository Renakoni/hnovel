package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import com.github.michaelbull.result.get
import hnovel.content.DiscoveryRoutingFixtures
import hnovel.content.RuleSourceFixture
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.discovery.discoverySources
import io.nightfish.lightnovelreader.api.web.discovery.DiscoverySection
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class DiscoveryRoutingTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun reviewedSamplesReachExactlyTheirAdvertisedSourceTabsWithoutExecutingSourceCode() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.afterRun = { error("Capability registration must not execute source rules") }
            val authority = ExecutionAuthority()
            val registry = WebSourceRegistry(authority)
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir() = directory.root
            }
            val imported = ImportedRuleSources(context, registry, authority, SourceSessionManager(authority), fixture.runner)
            try {
                for (sample in DiscoveryRoutingFixtures.samples) {
                    val name = sample.getValue("id").jsonPrimitive.content
                    val raw = JsonObject(fixture.raw(name) + sample.getValue("fields").jsonObject)
                    val preview = imported.importer.preview(raw.toString())
                    val committed = imported.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                    val id = imported.activate(committed.items.single().reference!!,
                        listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))
                    val expected = sample.getValue("expected").jsonObject
                    val sources = registry.sources.value
                    assertEquals(name, expected.getValue("hasFeed").jsonPrimitive.boolean,
                        discoverySources(sources, SourceCapability.Explore).any { it.metadata.id == id })
                    assertEquals(name, expected.getValue("hasCategories").jsonPrimitive.boolean,
                        discoverySources(sources, SourceCapability.Categories).any { it.metadata.id == id })
                    assertTrue(name, SourceCapability.Search in sources.single { it.metadata.id == id }.metadata.capabilities)
                }
                assertEquals(0, fixture.documents.get())
            } finally { imported.stop() }
        }
    }

    @Test fun genreOnlyCatalogHasTagsAndDoesNotFetchAPreview() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> definition(raw, "玄幻::/search?tag=fantasy&&科幻::/search?tag=scifi") }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                assertTrue(provider.hasCategories)
                assertFalse(provider.hasFeed)
                assertEquals(listOf("玄幻", "科幻"), provider.catalog().get()!!.categories.map { it.title })
                assertTrue(provider.feed().get()!!.isEmpty())
                assertEquals(0, fixture.documents.get())
            }
        }
    }

    @Test fun recentAndRankingPreviewsContainBooksBeforeOpeningMore() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> definition(raw,
                "玄幻::/search?tag=fantasy&&最近更新::/search?sort=new&page={{page}}&&总排行榜::/search?sort=rank&page={{page}}")
            }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val categories = provider.catalog().get()!!.categories
                assertEquals(3, categories.size)
                assertEquals(0, fixture.documents.get())
                val snapshots = mutableListOf<List<DiscoverySection>>()
                provider.feedUpdates().collect { snapshots += it.get()!! }
                assertEquals(listOf(2, 2, 2), snapshots.map { it.size })
                val feed = snapshots.last()
                assertEquals(listOf("最近更新", "总排行榜"), feed.map { it.title })
                assertTrue(feed.all { it.books.isNotEmpty() && it.more != null })
                assertEquals(categories.drop(1).map { it.id }, feed.map { it.categoryId })
                assertEquals(categories.drop(1).map { it.target }, feed.map { it.more })
                assertEquals(2, fixture.documents.get())
                assertEquals(setOf("/search?sort=new&page=1", "/search?sort=rank&page=1"),
                    List(2) { fixture.server.takeRequest(1, TimeUnit.SECONDS)?.path }.toSet())
            }
        }
    }

    @Test fun directHomepageWithoutACatalogProvidesOnlyDiscovery() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "homepageModules" to JsonPrimitive("""[{"key":"recent","type":"card","title":"最近更新","url":"/search?page={{page}}"}]"""),
                "ruleExplore" to raw.getValue("ruleSearch")))
            }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                assertTrue(provider.hasFeed)
                assertFalse(provider.hasCategories)
                val section = provider.feed().get()!!.single()
                assertEquals("最近更新", section.title)
                assertTrue(section.books.isNotEmpty())
                assertNull(section.categoryId)
                assertEquals(1, fixture.documents.get())
            }
        }
    }

    @Test fun automaticPreviewsAreBoundedButAllRankTagsRemainReachable() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> definition(raw, (0 until 326).joinToString("&&") {
                "最近更新 $it::/search?list=$it&page={{page}}"
            }) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val catalog = provider.catalog().get()!!
                assertEquals(326, catalog.categories.size)
                val feed = provider.feed().get()!!
                assertEquals(8, feed.size)
                assertTrue(feed.all { it.books.isNotEmpty() })
                assertEquals(catalog.categories.take(8).map { it.target }, feed.map { it.more })
                assertEquals(8, fixture.documents.get())
                val last = provider.page(io.nightfish.lightnovelreader.api.web.discovery.DiscoveryRequest(catalog.categories.last().target)).get()!!
                assertTrue(last.books.isNotEmpty())
                assertEquals(9, fixture.documents.get())
            }
        }
    }

    @Test fun cancellingInferredPreviewsStopsQueuedListsWhileRequestsAreInFlight() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val concurrency = RuleDiscoveryProvider.PREVIEW_CONCURRENCY
            val entered = CountDownLatch(concurrency)
            val release = CountDownLatch(1)
            val dispatcher = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    return dispatcher.dispatch(request)
                }
            }
            fixture.source { raw -> definition(raw, (0 until concurrency + 2).joinToString("&&") {
                "最近更新 $it::/search?list=$it"
            }) }.use { source ->
                val pending = launch(Dispatchers.Default) { RuleDiscoveryProvider(source).feedUpdates().collect {} }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                    withTimeout(10000) { pending.cancelAndJoin() }
                    assertEquals(concurrency, fixture.server.requestCount)
                } finally { release.countDown(); pending.cancelAndJoin() }
            }
        }
    }

    @Test fun dynamicGenreOnlyResultCanBecomeAFeedAfterItsOwnControlRefreshes() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(definition(raw,
                "@js:infoMap.Mode==='feed'?[{title:'最近更新',url:'/search'}]:[{title:'玄幻',url:'/search?tag=fantasy'}]") +
                ("exploreScreen" to JsonPrimitive("""[{"title":"Mode","type":"select","chars":["tags","feed"],"action":"java.refreshExplore()"}]""")))
            }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                assertTrue(provider.hasFeed)
                assertTrue(provider.feed().get()!!.isEmpty())
                assertEquals(0, fixture.documents.get())
                assertTrue(provider.interact("Mode", "feed").get()!!.refresh)
                val feed = provider.feed().get()!!.single()
                assertEquals("最近更新", feed.title)
                assertTrue(feed.books.isNotEmpty())
                assertEquals(1, fixture.documents.get())
            }
        }
    }

    private fun definition(raw: JsonObject, catalog: String) = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive(catalog), "ruleExplore" to raw.getValue("ruleSearch")))
}
