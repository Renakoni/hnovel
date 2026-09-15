package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleDiscoveryClassifierTest {
    @Test fun reviewedDesktopCatalogsAndExplicitHomepageKeepTheirExpectedCapabilitiesAndLists() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (sample in DiscoveryRoutingFixtures.samples) {
                val id = sample.getValue("id").jsonPrimitive.content
                val expected = sample.getValue("expected").jsonObject
                fixture.source(id) { raw -> JsonObject(raw + sample.getValue("fields").jsonObject) }.use { source ->
                    assertEquals(id, expected.getValue("hasFeed").jsonPrimitive.boolean, source.canFeed)
                    assertEquals(id, expected.getValue("hasCategories").jsonPrimitive.boolean, source.canCategorize)
                    assertEquals(id, sample.getValue("hasSearch").jsonPrimitive.boolean, source.canSearch)
                    if (source.canDiscover && expected.getValue("resolution").jsonPrimitive.content == "static") {
                        val catalog = source.openDiscovery(id).catalog()
                        assertEquals(id, expected.getValue("feedTitles").jsonArray.map { it.jsonPrimitive.content },
                            RuleDiscoveryClassifier.feed(catalog).map { it.title })
                    }
                }
            }
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun inferenceKeepsOriginalTargetsAndIdsAndStopsAtTheNextGroup() {
        val rows = listOf(
            row("genres", "分类"), row("fantasy", "玄幻", "/fantasy?page={{page}}"),
            row("rank", "排 行 榜"), row("daily", "日榜", "/rank?period=day"),
            row("same-name-a", "玄幻", "/rank?tag=fantasy"), row("same-name-b", "玄幻", "/rank?tag=other"),
            row("next", "题材"), row("mystery", "推理", "/mystery"),
            row("unknown", "编辑的小角落", "/unknown"), row("unnamed", "", "/unnamed"),
        )
        val feed = RuleDiscoveryClassifier.feed(RuleDiscoveryCatalog(rows, emptyMap()))
        assertEquals(listOf("daily", "same-name-a", "same-name-b"), feed.map { it.id })
        assertEquals(listOf("日榜", "排 行 榜 · 玄幻", "排 行 榜 · 玄幻"), feed.map { it.title })
        assertEquals(rows.slice(3..5).map { it.url }, feed.map { it.url })
    }

    @Test fun decoratedChineseAndEnglishListNamesWorkWithoutTreatingGenresOrControlsAsPreviews() {
        val titles = listOf("🔖最 新 章 节🔖", "今　日　更　新", "【新书上架】", "<b>月票榜</b>", "Ｎｅｗ Releases", "Popular")
        val rows = listOf(row("genre", "热血青春", "/genre"), row("status", "完结", "/complete"),
            row("tag", "推理", "/mystery"), RuleDiscoveryRow("input", "排行", "select", choices = listOf("new"))) +
            titles.mapIndexed { i, title -> row("$i", title, "/list/$i") }
        assertEquals(titles, RuleDiscoveryClassifier.feed(RuleDiscoveryCatalog(rows, emptyMap())).map { it.title })
    }

    @Test fun automaticPreviewsAreBoundedAndExplicitHomepageCanOverrideOrDisableInference() {
        val rows = List(326) { row("$it", "最近更新 $it", "/list/$it") }
        assertEquals(rows.take(6), RuleDiscoveryClassifier.feed(RuleDiscoveryCatalog(rows, emptyMap())))
        val explicit = List(8) { row("home:$it", "Editorial $it", "/editorial/$it") }
        assertEquals(explicit, RuleDiscoveryClassifier.feed(RuleDiscoveryCatalog(rows, emptyMap(), explicit)))
        assertTrue(RuleDiscoveryClassifier.feed(RuleDiscoveryCatalog(rows, emptyMap(), emptyList())).isEmpty())
        assertEquals(326, rows.size)
    }

    @Test fun staticEmptyAndHeadingsAreAbsentButDynamicEmptyAndMalformedCatalogsStayRetryable() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (catalog in listOf("[]", "[{\"title\":\"\",\"url\":null,\"style\":{}}]", "分类",
                """[{"title":"Header","viewName":"'最近更新'","url":""}]""")) {
                fixture.source { JsonObject(it + ("exploreUrl" to JsonPrimitive(catalog))) }.use { source ->
                    assertFalse(source.canFeed)
                    assertFalse(source.canCategorize)
                    assertTrue(source.canSearch)
                }
            }
            fixture.source { JsonObject(it + ("exploreUrl" to JsonPrimitive("@js:[]"))) }.use { source ->
                assertTrue(source.canFeed)
                assertTrue(source.canCategorize)
                assertTrue(source.openDiscovery("dynamic").catalog().rows.isEmpty())
                assertTrue(source.canFeed)
                assertTrue(source.canCategorize)
            }
            fixture.source { JsonObject(it + ("exploreUrl" to JsonPrimitive("[{\"type\":\"unknown\"}]"))) }.use { source ->
                assertTrue(source.canFeed)
                assertTrue(source.canCategorize)
                try { source.openDiscovery("invalid").catalog(); fail("Invalid rules must retain a field diagnostic") }
                catch (failure: SourceContentException) { assertEquals("exploreUrl[0].type", failure.field) }
            }
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun bookMetadataAndSourceGroupsCannotCreateBrowseCapabilities() {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "bookSourceGroup" to JsonPrimitive("最新更新,玄幻"),
                "ruleExplore" to buildJsonObject { put("kind", "$.tags"); put("lastChapter", "$.lastChapter") }))
            }.use { source ->
                assertTrue(source.canSearch)
                assertFalse(source.canFeed)
                assertFalse(source.canCategorize)
            }
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun onlyUnscopedControlsProvideACategoryPageAndDynamicTitlesRemainEligible() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (scope in listOf("", ",\"targetPrefixes\":[\"/search\"]")) {
                fixture.source { raw -> JsonObject(raw + ("exploreScreen" to JsonPrimitive(
                    """[{"title":"Sort","type":"select","viewName":"'Latest'","chars":["new","hot"]$scope}]"""))) }.use { source ->
                    assertFalse(source.canFeed)
                    assertEquals(scope.isEmpty(), source.canCategorize)
                }
            }
            fixture.source { raw -> JsonObject(raw + ("exploreUrl" to JsonPrimitive(
                """[{"title":"Sort","type":"select","viewName":"'Latest'","chars":["new","hot"]}]"""))) }.use { source ->
                assertFalse(source.canFeed)
                assertTrue(source.canCategorize)
            }
            fixture.source { raw -> JsonObject(raw + ("exploreUrl" to JsonPrimitive(
                """[{"title":"Label","viewName":"'最近更新'","url":"/search"}]"""))) }.use { source ->
                assertTrue(source.canFeed)
                assertEquals("最近更新", RuleDiscoveryClassifier.feed(source.openDiscovery("title").catalog()).single().title)
            }
            assertEquals(0, fixture.documents.get())
        }
    }

    private fun row(id: String, title: String, url: String = "") = RuleDiscoveryRow(id, title, "url", url)
}
