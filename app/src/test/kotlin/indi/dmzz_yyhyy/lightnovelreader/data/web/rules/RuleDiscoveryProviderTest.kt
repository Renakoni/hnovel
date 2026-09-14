package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import com.github.michaelbull.result.*
import hnovel.content.RuleSourceFixture
import hnovel.content.DiscoveryCatalogFixtures
import hnovel.content.RuleDiscoverySession
import hnovel.content.SourceContentException
import hnovel.content.ContentError
import hnovel.content.SourceVerification
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun challengeInSecondModuleRetriesOnlyThatModule() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
            ("homepageModules" to JsonPrimitive("""[
                {"key":"first","type":"card","title":"First","url":"/search?module=1"},
                {"key":"second","type":"card","title":"Second","url":"/search?module=2"}
            ]"""))) }.use { source ->
            val original = source.openDiscovery("original")
            val catalog = original.catalog(homepage = true)
            val books = original.page("/search", 1, emptyMap())
            val verification = mockk<SourceVerification> {
                every { kind } returns hnovel.network.BrowserChallengeKind.Cloudflare
                coEvery { complete() } returns Unit
            }
            val session = mockk<RuleDiscoverySession>()
            coEvery { session.catalog(homepage = true) } returns catalog
            coEvery { session.page("/search?module=1", 1, any()) } returns books
            var attempts = 0
            coEvery { session.page("/search?module=2", 1, any()) } coAnswers {
                if (++attempts == 1) throw SourceContentException(ContentError.BrowserRequired,
                    "ruleExplore", verification = verification)
                books
            }
            val owner = VerificationOwner(Identifier("rules", "progressive"), "revision", 0)
            val listings = MutableStateFlow(listOf(SourceListing(SourceMetadata(
                WebDataSourceItem(owner.source, "Fixture", ""), emptySet(), revision = owner.revision), SourceStatus.Ready)))
            val coordinator = SourceVerificationCoordinator(mockk<WebSourceRegistry> { every { sources } returns listings })
            val provider = RuleDiscoveryProvider(source, session, RuleRequestRecovery(coordinator, owner, "Fixture"))
            val sizes = mutableListOf<Int>()
            withContext(ForegroundSourceRequest()) { provider.feedUpdates().collect { sizes += it.get()!!.size } }
            assertEquals(listOf(1, 2), sizes)
            coVerify(exactly = 1) { session.page("/search?module=1", 1, any()) }
            coVerify(exactly = 2) { session.page("/search?module=2", 1, any()) }
            coVerify(exactly = 1) { verification.complete() }
        } }
    }

    @Test fun stoppingAfterFirstHomepageSnapshotDoesNotFetchLaterModules() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
            ("homepageModules" to JsonPrimitive("""[
                {"key":"first","type":"card","title":"First","url":"/search?module=1"},
                {"key":"second","type":"card","title":"Second","url":"/search?module=2"}
            ]"""))) }.use { source ->
            val first = RuleDiscoveryProvider(source).feedUpdates().first().get()!!
            assertEquals(listOf("First"), first.map { it.title })
            assertEquals(1, first.single().books.size)
            assertEquals(1, fixture.documents.get())
            assertEquals("/search?module=1", fixture.server.takeRequest().path)
        } }
    }

    @Test fun homepageSnapshotsAreImmutableAndLaterFailureKeepsEarlierSuccess() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
            ("homepageModules" to JsonPrimitive("""[
                {"key":"first","type":"card","title":"First","url":"/search?module=1"},
                {"key":"second","type":"card","title":"Second","url":"/search?module=2"}
            ]"""))) }.use { source ->
            val provider = RuleDiscoveryProvider(source)
            val updates = mutableListOf<Result<List<DiscoverySection>, DiscoveryError>>()
            provider.feedUpdates().collect {
                updates += it
                fixture.status = 503
            }
            assertEquals(2, updates.size)
            assertEquals(listOf("First"), updates.first().get()!!.map { it.title })
            assertEquals(Err(DiscoveryError.Network), updates.last())
            fixture.status = 200
            val complete = mutableListOf<List<DiscoverySection>>()
            provider.feedUpdates().collect { complete += it.get()!! }
            assertEquals(listOf(1, 2), complete.map { it.size })
            assertEquals(listOf("First", "Second"), complete.last().map { it.title })
            assertEquals(1, updates.first().get()!!.size)
        } }
    }

    @Test fun scopedResultFiltersStayOutOfHomepageCategoriesAndOtherLists() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) + mapOf(
            "exploreUrl" to JsonPrimitive("""[{"title":"Tag","url":"/tags/fixture?sort={{infoMap.Sort}}"}]"""),
            "exploreScreen" to JsonPrimitive("""[{"title":"Sort","type":"select","chars":["new","popular"],"targetPrefixes":["/search?","/tags/"]}]"""),
            "homepageModules" to JsonPrimitive("""[{"key":"articles","type":"card","title":"Articles","url":"/search?sort={{infoMap.Sort}}"}]""")
        )) }.use { source ->
            val provider = RuleDiscoveryProvider(source)
            assertTrue(provider.homepageCatalog().get()!!.filters.isEmpty())
            assertEquals("new", provider.catalog().get()!!.values["Sort"])
            assertTrue(provider.catalog().get()!!.filters.isEmpty())
            assertTrue(provider.filters("/rank?type=day").isEmpty())
            assertEquals("Sort", provider.filters("/search?sort={{infoMap.Sort}}").single().id)
            assertEquals("Sort", provider.filters("/tags/fixture?sort={{infoMap.Sort}}").single().id)
            assertTrue(provider.page(DiscoveryRequest("/search?sort={{infoMap.Sort}}", filters = mapOf("Sort" to "popular"))).get()!!.books.isNotEmpty())
            assertEquals("/search?sort=popular", fixture.server.takeRequest().path)
        } }
    }

    @Test fun directHomepageControlsAndResultsDoNotEvaluateFailingCategories() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) + mapOf(
            "exploreUrl" to JsonPrimitive("@js:throw 'category unavailable'"),
            "exploreScreen" to JsonPrimitive("""[{"title":"Sort","type":"select","chars":["new","popular"],"default":"new","action":"java.refreshExplore()"}]"""),
            "homepageModules" to JsonPrimitive("""[{"key":"articles","type":"card","title":"Articles","url":"/search?sort={{infoMap.Sort}}&page={{page}}"}]""")
        )) }.use { source ->
            val provider = RuleDiscoveryProvider(source)
            assertEquals("new", provider.homepageCatalog().get()!!.values["Sort"])
            assertTrue(provider.interact("Sort", "popular").get()!!.refresh)
            assertEquals("popular", provider.homepageCatalog(true).get()!!.values["Sort"])
            val feed = provider.feed().get()!!.single()
            assertEquals("Articles", feed.title)
            assertNull(feed.categoryId)
            assertEquals("/search?sort=popular&page=1", fixture.server.takeRequest().path)
            val result = provider.openSession("result", mapOf("Sort" to "new"), DiscoveryEnvironment())
            result.homepageCatalog()
            assertTrue(result.page(DiscoveryRequest(feed.more!!, filters = mapOf("Sort" to "new"))).get()!!.books.isNotEmpty())
            assertEquals("/search?sort=new&page=1", fixture.server.takeRequest().path)
            assertEquals(Err(DiscoveryError.InvalidRules), provider.catalog())
        } }
    }

    @Test fun nativeSiteChallengeIsNotReportedAsMissingAccountLogin() = runBlocking {
        for (kind in hnovel.network.BrowserChallengeKind.entries) {
            val browser = hnovel.network.BrowserExecutor { _, request, _, _ ->
                hnovel.network.BrokerResult.Failure(hnovel.network.RequestStage.Response, hnovel.network.FailureCode.BrowserRequired,
                    challenge = kind, verificationRequest = request)
            }
            RuleSourceFixture(browser).use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
                ("browserRead" to JsonPrimitive(true))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val expected = if (kind == hnovel.network.BrowserChallengeKind.Login) DiscoveryError.AuthenticationRequired
                    else DiscoveryError.VerificationRequired
                assertEquals(Err(expected), provider.page(DiscoveryRequest("/search")))
            } }
        }
    }

    @Test fun declaredHomepageKeepsNavigationSeparateFromTagCategories() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("""[
                    {"title":"Ranking","url":"/search?kind=rank&page={{page}}"},
                    {"title":"Articles","url":"/search?kind=articles&page={{page}}"},
                    {"title":"Fantasy","url":"/search?tag=fantasy&page={{page}}"},
                    {"title":"Science fiction","url":"/search?tag=scifi&page={{page}}"}
                ]"""),
                "homepageModules" to JsonPrimitive("""[
                    {"key":"rank","type":"ranking","title":"Ranking","kindTitle":"Ranking"},
                    {"key":"articles","type":"card","title":"Articles","kindTitle":"Articles"}
                ]"""),
                "ruleExplore" to raw.getValue("ruleSearch"))) }
            val provider = RuleDiscoveryProvider(source)
            val categories = provider.catalog().get()!!.categories
            assertEquals(listOf("Fantasy", "Science fiction"), categories.map { it.title })
            assertEquals(0, fixture.documents.get())
            val feed = provider.feed().get()!!
            assertEquals(listOf("Ranking", "Articles"), feed.map { it.title })
            assertTrue(feed.all { it.books.isNotEmpty() })
            assertEquals("/search?kind=articles&page={{page}}", feed.last().more)
            assertEquals(2, fixture.documents.get())
            assertTrue(provider.page(DiscoveryRequest(categories.first().target)).get()!!.books.isNotEmpty())
            assertEquals(3, fixture.documents.get())
        }
    }

    @Test fun missingHomepageTargetReportsItsSourceField() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { raw -> JsonObject(definition(raw) + ("homepageModules" to
                JsonPrimitive("""[{"key":"rank","type":"ranking","title":"Ranking","kindTitle":"Missing"}]"""))) }
            val provider = RuleDiscoveryProvider(source)
            assertEquals(Err(DiscoveryError.InvalidRules), provider.catalog())
            assertEquals("homepageModules[0].kindTitle", provider.failureField)
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun deniedCategoryPageReportsOnlyItsOriginAndResourcePurpose() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("""[{"title":"Books","url":"https://api.invalid:8443/private?token=secret"}]"""),
                "ruleExplore" to raw.getValue("ruleSearch"))) }).use { source ->
                val provider = RuleDiscoveryProvider(source)
                val category = provider.catalog().get()!!.categories.single()
                assertEquals(Err(DiscoveryError.PermissionDenied), provider.page(DiscoveryRequest(category.target)))
                assertEquals(DiscoveryPermission("https://api.invalid:8443", "Document"), provider.permissionFailure)
                assertEquals(0, fixture.server.requestCount)
                provider.catalog()
                assertNull(provider.permissionFailure)
            }
        }
    }
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
                assertEquals(Err(DiscoveryError.Network), aPage.loadMore())
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

    @Test fun realQidianCatalogReachesTheHostWithoutLosingTargetsOrFetchingBooks() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rows = DiscoveryCatalogFixtures.rows(0)
            fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to JsonPrimitive(rows.toString()))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val catalog = provider.catalog().get()!!
                assertEquals(326, catalog.categories.size)
                assertEquals(rows.map { it.jsonObject.getValue("url").jsonPrimitive.content }, catalog.categories.map { it.target })
                assertEquals(catalog.categories, provider.categories().get())
                assertEquals(0, fixture.documents.get())
            }
        }
    }

    @Test fun largeFeedLoadsOnlyOnePreviewAndTheLastCategoryUsesItsOwnTarget() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rows = buildJsonArray { repeat(326) { i -> add(buildJsonObject {
                put("title", "Category $i"); put("url", "/search?category=$i&page={{page}}")
            }) } }
            val rule = fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to JsonPrimitive(rows.toString()))) }
            val id = Identifier("rules", rule.definition.sourceId)
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rule), SourceMetadata(WebDataSourceItem(id, "Large source", "Test"),
                setOf(SourceCapability.Categories, SourceCapability.Explore)))
            try {
                val source = (registry.resolve(id) as SourceResolution.Ready).runtime.discovery!!
                val categories = source.categories().get()!!
                assertEquals(0, fixture.documents.get())
                val feed = source.feed().get()!!
                assertEquals(326, feed.size)
                assertEquals(categories.map { it.id }, feed.map { it.categoryId })
                assertEquals(categories.map { it.target }, feed.map { it.more })
                assertEquals(listOf(1) + List(325) { 0 }, feed.map { it.books.size })
                assertEquals(1, fixture.documents.get())
                assertEquals("/search?category=0&page=1", fixture.server.takeRequest().path)
                val last = source.open(categories.last().target).loadMore().get()!!
                assertEquals(id, last.books.single().id.sourceId)
                assertEquals("/search?category=325&page=1", fixture.server.takeRequest().path)
                assertEquals(2, fixture.documents.get())
            } finally { registry.unregister(id) }
        }
    }

    @Test fun catalogLimitAndUnnamedTargetsHaveDifferentHostErrorsAndLocations() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val large = buildJsonArray { repeat(1025) { i -> add(buildJsonObject { put("title", "Row $i"); put("url", "/$i") }) } }
            for ((rows, error, field) in listOf(
                Triple(large, DiscoveryError.Limit, "exploreUrl"),
                Triple(DiscoveryCatalogFixtures.rows(18), DiscoveryError.InvalidRules, "exploreUrl[19].title"),
            )) fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to JsonPrimitive(rows.toString()))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                assertEquals(Err(error), provider.catalog())
                assertEquals(field, provider.failureField)
            }
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun pageLimitKeepsTheLastAllowedBooksAndReportsTheNextAttemptWithoutFetching() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val provider = RuleDiscoveryProvider(fixture.source(customize = ::definition))
            val last = provider.page(DiscoveryRequest("/search", "64")).get()!!
            assertEquals(1, last.books.size)
            assertEquals("65", last.nextCursor)
            assertEquals(Err(DiscoveryError.Limit), provider.page(DiscoveryRequest("/search", last.nextCursor)))
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

    @Test fun missingDisabledEmptyAndInvalidCataloguesRemainDifferentWithoutFetchingBooks() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val cases = listOf(
                fixture.raw("missing") to false,
                JsonObject(definition(fixture.raw("disabled")) + ("enabledExplore" to JsonPrimitive(false))) to false,
                JsonObject(definition(fixture.raw("empty")) + ("exploreUrl" to JsonPrimitive("[]"))) to true,
                JsonObject(definition(fixture.raw("invalid")) + ("exploreUrl" to JsonPrimitive("[{\"title\":\"Broken\",\"type\":\"nativeView\"}]"))) to true
            )
            for ((index, value) in cases.withIndex()) fixture.source { value.first }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                assertEquals(value.second, provider.hasCategories)
                assertEquals(value.second, provider.hasFeed)
                assertTrue(source.canSearch)
                when (index) {
                    0, 1 -> assertEquals(Err(DiscoveryError.Unsupported), provider.catalog())
                    2 -> assertTrue(provider.catalog().get()!!.categories.isEmpty())
                    3 -> {
                        assertEquals(Err(DiscoveryError.InvalidRules), provider.catalog())
                        assertEquals("exploreUrl[0].type", provider.failureField)
                    }
                }
            }
            assertEquals(0, fixture.documents.get())
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
