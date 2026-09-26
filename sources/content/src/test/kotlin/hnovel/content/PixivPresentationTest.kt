package hnovel.content

import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class PixivPresentationTest {
    private val original = Json.parseToJsonElement(File(
        "../../app/src/main/assets/source-catalog/Adult.json").readText()).jsonArray
        .map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.pixiv.net/novel" }
    private val titles = listOf("R18 推荐", "R18 今日榜", "R18 周榜", "R18 原创热门 · 男性",
        "R18 原创热门 · 女性", "常规推荐", "常规今日榜", "常规原创热门")
    private val browser = object : BrowserExecutor {
        override suspend fun defaultUserAgent() = "Test WebView-UA"
        override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
            guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("Unexpected browser request")
    }

    private fun definition(fixture: RuleSourceFixture, settings: String = "", localUrls: Boolean = true): JsonObject {
        // Run the shipped UI, catalogue, library and actions; only provide an isolated test account.
        val local = if (localUrls) Json.parseToJsonElement(original.toString().replace(
            "https://www.pixiv.net", fixture.server.url("/").toString().removeSuffix("/"))).jsonObject else original
        val setup = """
            if (!cache.get('pixivPresentationTest')) {
                cache.put('pixivUid','12345');
                cache.put('pixivCookie','PHPSESSID=12345_test');
                cache.put('pixivCsrfToken','test-token');
                cache.put('checkTimes','1');
                let settings = setDefaultSettings();
                $settings
                putInCacheObject('pixivSettings',settings);
                cache.put('pixivPresentationTest','ready');
            }
        """.trimIndent()
        return JsonObject(local + listOf("loginUi", "exploreUrl").associateWith { field ->
            JsonPrimitive("@js:" + setup + "\n" + local.getValue(field).jsonPrimitive.content.removePrefix("@js:"))
        })
    }

    @Test fun readerPanelHasOnlyReadingActionsAndNeverFetchesPollDetails() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { raw ->
                // Supply a stored reading context without depending on a live Pixiv book.
                JsonObject(definition(fixture, """
                    settings.SHOW_SETTINGS=true;settings.SHOW_DISCOVER=true;
                    settings.SHOW_SETTINGS2=true;settings.SHOW_DISCOVER2=true;
                    settings.SHOW_QUESTION=true;settings.SHOW_FURRY=true;
                """.trimIndent()) + raw.filterKeys { it in setOf(
                    "searchUrl", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "header") } + mapOf(
                    "loginCheckJs" to JsonPrimitive(""), "eventListener" to JsonPrimitive(false)))
            }.use { source ->
                val book = source.search("fixture").single()
                val chapter = source.directory(book.id).first { !it.isVolume }
                val before = fixture.server.requestCount
                source.openLoginSession(LoginReadingContext(book.id, chapter.id)).use { panel ->
                    val form = panel.loginForm()
                    assertEquals(listOf("收藏本章", "追更系列", "收藏系列", "取消收藏", "关注作者", "屏蔽作者", "刷新本章"),
                        form.fields.map { it.name.substringAfterLast(' ') })
                    assertTrue(form.fields.all { it.type == "button" || it.type == "toggle" })
                    assertEquals(before, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun configurationAndLocalListsAreOnlyInSourceSettings() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture,
                "settings.SHOW_SETTINGS=true;settings.SHOW_DISCOVER=true;settings.SHOW_FURRY=true;") }.use { source ->
                source.openLoginSession().use { panel ->
                    val names = panel.loginForm().fields.map { it.name.substringAfterLast(' ') }
                    assertTrue(names.containsAll(listOf("章节编号", "调试模式", "自动收藏", "分类设置",
                        "排行榜单", "原创热门", "添加屏蔽", "喜欢标签", "他人收藏", "文本框")))
                    assertTrue(names.none { it in listOf("章节名称", "收藏本章", "刷新本章", "发送评论",
                        "删除评论", "显示投票", "兽人小说", "兽人作者") })
                    assertEquals(0, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun settingsGroupsStillExpandAndCollapseWithoutRequests() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                source.openLoginSession().use { panel ->
                    var form = panel.loginForm()
                    assertTrue(form.fields.none { it.name.endsWith("章节编号") })
                    val expand = form.fields.single { it.name == "👀 书源设置" }
                    panel.login(form.values, expand.id, form.id)
                    form = panel.loginForm()
                    assertTrue(form.fields.any { it.name.endsWith("章节编号") })
                    val categories = form.fields.single { it.name == "👀 分类设置" }
                    panel.login(form.values, categories.id, form.id)
                    form = panel.loginForm()
                    assertTrue(form.fields.any { it.name.endsWith("原创热门") })
                    val collapse = form.fields.single { it.name == "👀 书源设置" }
                    panel.login(form.values, collapse.id, form.id)
                    assertTrue(panel.loginForm().fields.none { it.name.endsWith("章节编号") })
                    assertEquals(0, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun homepageIsEightUniqueListsWithoutFetchingCategoriesOrLegacyRecommendations() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture, "settings.SHOW_FURRY=true;") }.use { source ->
                val catalog = source.openDiscovery("homepage").catalog(homepage = true)
                val feed = RuleDiscoveryClassifier.feed(catalog)
                assertEquals(titles, feed.map { it.title })
                assertEquals(8, feed.map { it.url }.distinct().size)
                assertEquals(listOf(
                    "top/novel?mode=r18&lang=zh",
                    "ranking/novel?mode=daily_r18&content=novel&p={{page}}",
                    "ranking/novel?mode=weekly_r18&content=novel&p={{page}}",
                    "genre/novel/male?mode=r18&lang=zh",
                    "genre/novel/female?mode=r18&lang=zh",
                    "top/novel?mode=all&lang=zh",
                    "ranking/novel?mode=daily&content=novel&p={{page}}",
                    "genre/novel/all?mode=safe&lang=zh"), feed.map { it.url.substringAfter("/ajax/") })
                assertTrue(feed.none { it.url.contains("/bookmarks") || it.url.contains("/profile/all") })
                assertEquals(2, catalog.rows.count { it.url.contains("/novels/bookmarks?") })
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun categorySwitchesCannotRemoveFixedHomepageModules() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture, """
                settings.SHOW_ADULT=false;settings.SHOW_GENERAL=false;
                settings.SHOW_NEW_ADULT=false;settings.SHOW_NEW_GENERAL=false;
                settings.SHOW_RANK_ADULT=false;settings.SHOW_RANK_GENERAL=false;
                settings.SHOW_GENRE_ADULT=false;settings.SHOW_GENRE_GENERAL=false;settings.SHOW_ABOUT=false;
            """.trimIndent()) }.use { source ->
                val catalog = source.openDiscovery("categories-disabled").catalog(homepage = true)
                assertEquals(titles, RuleDiscoveryClassifier.feed(catalog).map { it.title })
                assertEquals(8, catalog.rows.count { it.type == "url" })
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun homepageRetainsSourceDirectConnectionOptions() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) {
                definition(fixture, "settings.IPDirect=true;", localUrls = false)
            }.use { source ->
                val feed = RuleDiscoveryClassifier.feed(source.openDiscovery("direct").catalog(homepage = true))
                assertEquals(titles, feed.map { it.title })
                assertTrue(feed.all { it.url.startsWith("https://210.140.139.155/") && it.url.contains("X-csrf-token") })
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun previewsRequestOnlySelectedListsAndBookmarksRemainOnDemand() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":false,"body":{"page":{"recommend":{"ids":[]}},
                        "thumbnails":{"novel":[],"novelSeries":[]},"display_a":{"rank_a":[]},"works":[]}}""")
            }
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                val discovery = source.openDiscovery("requests")
                val catalog = discovery.catalog(homepage = true)
                assertEquals(0, fixture.server.requestCount)
                for (row in RuleDiscoveryClassifier.feed(catalog)) {
                    assertTrue(discovery.preview(row.url, emptyMap()).books.isEmpty())
                    // The shipped loginCheckJs explicitly refetches the same request.
                    repeat(2) {
                        val request = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                        assertEquals(row.url.removePrefix(fixture.server.url("/").toString().removeSuffix("/"))
                            .replace("{{page}}", "1"), request.path)
                    }
                }
                assertEquals(16, fixture.server.requestCount)
                val bookmark = catalog.rows.first { it.url.contains("/novels/bookmarks?") }
                assertTrue(discovery.preview(bookmark.url, emptyMap()).books.isEmpty())
                assertEquals(18, fixture.server.requestCount)
                assertEquals("/ajax/user/12345/novels/bookmarks",
                    fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
            }
        }
    }

    @Test fun removedFeaturesHaveNoRemainingEntrypointsImplementationsOrEndpoints() {
        val serialized = original.toString()
        for (removed in listOf("novelCommentAdd", "novelCommentDelete", "getNovelCommentID", "splitComments",
            "novelPollAnswer", "getPollData", "pollData", "问卷调查", "SHOW_QUESTION", "SHOW_FURRY",
            "urlLinpxAuthors", "api.linpx.ink", "furrynovel", "getFurryAuthors", "updatePixivAuthors")) {
            assertFalse(removed, serialized.contains(removed))
        }
        assertTrue(original.getValue("ruleContent").jsonObject.getValue("content").jsonPrimitive.content
            .contains("getComment(resp.body, content)"))
        assertTrue(original.getValue("ruleToc").jsonObject.getValue("chapterList").jsonPrimitive.content
            .contains("ADD_CHAPTER_INDEX"))
    }
}
