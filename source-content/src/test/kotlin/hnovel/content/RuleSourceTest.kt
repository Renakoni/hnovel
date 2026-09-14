package hnovel.content

import hnovel.execution.*
import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleSourceTest {
    @Test fun declaredBrowserReadsAndLoginHooksAcceptDocumentsWithoutClaimingHttpSuccess() = runBlocking {
        val requests = mutableListOf<String>()
        val browser = BrowserExecutor { _, request, _, _ ->
            requests += request.url
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(),
                "<li><a href='/book/one'><h2>Browser novel</h2></a></li>".toByteArray(), "UTF-8", 0,
                protocol = "", kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "browserRead" to JsonPrimitive(true),
                "loginCheckJs" to JsonPrimitive("if(!result.isBrowserDocument() || result.code()!==0 || result.getUrl()!==java.getUrl())throw 'wrong document';result;"),
                "ruleSearch" to buildJsonObject { put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href") }
            )) }).use { source ->
                assertEquals("Browser novel", source.search("fixture").single().title)
                assertEquals(1, requests.size)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun cataloguePageArraysAreExpandedOnceAndKeepScriptChapterUrls() = runBlocking {
        val rendered = mutableListOf<String>()
        val browser = BrowserExecutor { session, request, _, guard ->
            rendered += java.net.URI(request.url).path
            session.execute(request.copy(browser = null), guard)
        }
        RuleSourceFixture(browser).use { fixture ->
            val normal = fixture.server.dispatcher
            val pages = mutableListOf<String>()
            var expansions = 0
            fixture.afterRun = { task ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleToc.nextTocUrl") expansions++
            }
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path = request.path.orEmpty()
                    if (!path.startsWith("/toc/")) return normal.dispatch(request)
                    pages += path
                    val entries = when (path) {
                        "/toc/1" -> """{"sort":1,"chapterName":"One"}"""
                        "/toc/2" -> """{"sort":2,"chapterName":"Two words"}"""
                        "/toc/3" -> """{"sort":3,"chapterName":"Three"}"""
                        else -> ""
                    }
                    return okhttp3.mockwebserver.MockResponse().setBody("""{"chapters":[$entries]}""")
                }
            }
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "ruleToc" to buildJsonObject {
                    put("chapterList", "$.chapters"); put("chapterName", "$.chapterName")
                    put("chapterUrl", "<js>baseUrl.replace(/toc\\/\\d+/, 'c/{{$.sort}}')+'?cName={{$.chapterName}}'+\",{'webView':true}\"</js>\n")
                    put("nextTocUrl", "<js>[baseUrl,'/toc/2','/toc/3','/toc/4']</js>\n")
                },
                "ruleContent" to buildJsonObject { put("content", "article@html") }
            )) }).use { source ->
                val id = fixture.server.url("/book/one").toString()
                val chapters = source.directory(id)
                assertEquals(listOf("One", "Two words", "Three"), chapters.map { it.title })
                assertEquals(chapters.mapIndexed { index, chapter -> fixture.server.url("/c/${index + 1}").toString() +
                    "?cName=${chapter.title},{'webView':true}" }, chapters.map { it.id })
                assertEquals(listOf("/toc/1", "/toc/2", "/toc/3", "/toc/4"), pages)
                assertEquals(1, expansions)
                source.content(id, chapters.first().id)
                assertEquals(listOf("/c/1"), rendered)
            }
        }
    }

    @Test fun namelessRowsAndInvalidOptionalCoversDoNotDiscardSearchResults() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse()
                    .setBody("<li><a>Advertisement</a></li><li><a href='/book/one'><h2>Novel</h2></a><img src='javascript:void(0)'></li>")
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to buildJsonObject {
                put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href")
                put("author", "@js:if(!book.name)throw 'nameless row';'Author'")
                put("coverUrl", "img@src")
            })) }).use { source ->
                val book = source.search("novel").single()
                assertEquals("Novel", book.title)
                assertEquals("Author", book.author)
                assertEquals("", book.coverUrl)
                assertEquals(fixture.server.url("/book/one").toString(), book.id)
            }
        }
    }

    @Test fun cookieRefreshChallengeRetriesWithTheCapturedCookie() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            var challenge = true
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.path?.startsWith("/search") == true && challenge) {
                        challenge = false
                        return okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                            .setHeader("Set-Cookie", "_wa_=challenge; Path=/; Max-Age=30")
                            .setBody("<meta http-equiv=refresh content=0>")
                    }
                    return normal.dispatch(request)
                }
            }
            fixture.source().use { source ->
                assertEquals("Same title", source.search("title").single().title)
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }

    @Test fun exploreRuleWithoutBookListReusesSearchListSelectors() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw +
                ("ruleExplore" to buildJsonObject { put("author", "b@text") }))
            }).use { source ->
                assertEquals("Same title", source.discovery(fixture.server.url("/search").toString()).single().title)
            }
        }
    }

    @Test fun ruleBrowserCallsInheritHeadersAndRefreshThemAfterVerification() = runBlocking {
        for (dynamic in listOf(false, true)) {
            val browserPaths = mutableListOf<String>()
            val tokens = java.util.concurrent.atomic.AtomicInteger()
            val generation = java.util.concurrent.atomic.AtomicReference("before")
            val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val missing = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val browser = BrowserExecutor { session, request, _, guard ->
                browserPaths += java.net.URI(request.url).path
                val response = session.execute(request.copy(browser = null), guard)
                if (request.url.endsWith("/await")) generation.set("after")
                response
            }
            RuleSourceFixture(browser).use { fixture ->
                fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                        if (request.path == "/token") return okhttp3.mockwebserver.MockResponse()
                            .setBody("${generation.get()}-${tokens.incrementAndGet()}")
                        seen += request.path!!
                        val token = if (dynamic) "${generation.get()}-${tokens.get()}" else "static"
                        val accepted = request.getHeader("Authorization") == token && request.getHeader("User-Agent") == "source-agent" &&
                            request.getHeader("Cookie") == "source=login"
                        if (!accepted) missing += request.path!!
                        return okhttp3.mockwebserver.MockResponse().setBody(if (accepted) "accepted" else "missing header")
                    }
                }
                val url = fixture.server.url("/").toString()
                fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                    "header" to JsonPrimitive(if (dynamic)
                        "@js:JSON.stringify({Authorization:java.ajax('/token'),'User-Agent':'source-agent',Cookie:'source=login'})"
                        else """{"Authorization":"static","User-Agent":"source-agent","Cookie":"source=login"}"""),
                    "loginUrl" to JsonPrimitive("""
                        function login(){
                            if(java.webView(null,'${url}view','')!=='accepted')throw 'view header';
                            if(java.webViewGetSource(null,'${url}source','','')!=='accepted')throw 'source header';
                            if(java.webViewGetOverrideUrl(null,'${url}override','','')!=='accepted')throw 'override header';
                            if(java.getVerificationCode('${url}captcha')!=='accepted')throw 'captcha header';
                            java.startBrowser('/start','verify');
                            if(java.startBrowserAwait('/rendered','verify',false).body()!=='accepted')throw 'rendered header';
                            if(java.startBrowserAwait('/await','verify').body()!=='accepted')throw 'refetch header';
                        }
                    """.trimIndent())
                )) }).use { source -> source.login(emptyMap()) }
                assertEquals(listOf("/view", "/source", "/override", "/captcha", "/start", "/rendered", "/await"), browserPaths)
                assertEquals(browserPaths + "/await", seen.toList())
                assertTrue(missing.toString(), missing.isEmpty())
                assertEquals(if (dynamic) 8 else 0, tokens.get())
            }
        }
    }

    @Test fun directBrowserLoginUsesEvaluatedSourceHeadersOnItsFirstRequest() = runBlocking {
        for (dynamic in listOf(false, true)) {
            var navigations = 0
            val browser = BrowserExecutor { session, request, options, guard ->
                assertTrue(options.interactive)
                navigations++
                session.execute(request.copy(browser = null), guard)
            }
            RuleSourceFixture(browser).use { fixture ->
                fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        okhttp3.mockwebserver.MockResponse().setResponseCode(
                            if (request.getHeader("User-Agent") == "source-agent" &&
                                request.getHeader("Authorization") == "Bearer alice" &&
                                request.getHeader("Cookie") == "source=login") 200 else 401)
                            .setHeader("Content-Type", "text/html").setBody("<title>Login</title>")
                }
                val header = """{"User-Agent":"source-agent","Authorization":"Bearer alice","Cookie":"source=login"}"""
                fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                    "loginUrl" to JsonPrimitive(fixture.server.url("/login").toString()),
                    "loginUi" to JsonPrimitive("""[{"name":"user"}]"""),
                    "header" to JsonPrimitive(if (dynamic) """@js:JSON.stringify({
                        'User-Agent':'source-agent',Authorization:'Bearer '+source.getLoginInfoMap().get('user'),Cookie:'source=login'})""" else header)
                )) }).use { source ->
                    source.login(mapOf("user" to "alice"))
                    assertEquals(1, navigations)
                    assertEquals(1, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun nestedRuleRequestsInheritStaticAndScriptSourceHeaders() = runBlocking {
        for (dynamic in listOf(false, true)) RuleSourceFixture().use { fixture ->
            val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    seen.add(request.path!!.substringBefore('?'))
                    if (request.path == "/explicit") return okhttp3.mockwebserver.MockResponse().setResponseCode(
                        if (request.getHeader("Authorization") == null && request.getHeader("X-Api-Key") == null &&
                            request.getHeader("User-Agent") == "explicit-agent") 200 else 401)
                    if (request.getHeader("Authorization") != "Bearer source" ||
                        request.getHeader("X-Api-Key") != "source-key" ||
                        request.getHeader("User-Agent") != if (request.path == "/override") "request-agent" else "source-agent")
                        return okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                    return if (request.path!!.substringBefore('?') in setOf("/search", "/retry"))
                        okhttp3.mockwebserver.MockResponse().setBody("<li><a href='/book/one'><h2>Same title</h2></a></li>")
                    else okhttp3.mockwebserver.MockResponse().setBody("accepted")
                }
            }
            val header = """{"Authorization":"Bearer source","X-Api-Key":"source-key","User-Agent":"source-agent"}"""
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive(if (dynamic) "@js:JSON.stringify($header)" else header),
                "loginCheckJs" to JsonPrimitive("""
                    if (java.ajax('/ajax') !== 'accepted') throw 'ajax headers';
                    if (java.ajaxAll(['/batch-a','/batch-b']).some(function(r){return r.code()!==200})) throw 'batch headers';
                    if (java.connect('/override,{"headers":{"user-agent":"request-agent"}}').code()!==200) throw 'override headers';
                    if (java.connect('/explicit','{"User-Agent":"explicit-agent"}').code()!==200) throw 'explicit headers';
                    if (java.connect('/null',null).code()!==200) throw 'null headers';
                    java.connect('/retry')
                """.trimIndent()),
                "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject +
                    ("name" to JsonPrimitive("h2@text@js:if(java.ajax('/row')!=='accepted')throw 'row headers';result")))
            )) }).use { source ->
                assertEquals("Same title", source.search("title").single().title)
                assertEquals(setOf("/search", "/ajax", "/batch-a", "/batch-b", "/override", "/explicit", "/null", "/retry", "/row"), seen.toSet())
                assertEquals(9, seen.size)
            }
        }
    }

    @Test fun headerScriptCanFetchItsValueWithoutRecursivelyEvaluatingItself() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            val tokens = java.util.concurrent.atomic.AtomicInteger()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (request.path == "/token") okhttp3.mockwebserver.MockResponse().setBody("source-token").also { tokens.incrementAndGet() }
                    else if (request.getHeader("Authorization") == "source-token") normal.dispatch(request)
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(401)
            }
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive("@js:JSON.stringify({Authorization:java.ajax('/token')})"),
                "loginCheckJs" to JsonPrimitive("java.connect(result.url())")
            )) }).use { source ->
                assertEquals("Same title", source.search("title").single().title)
                assertEquals("Only the search and its explicit retry need headers", 2, tokens.get())
            }
        }
    }

    @Test fun localFieldsDoNotMultiplyHeaderRequestsAcrossReadingStages() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            val tokens = java.util.concurrent.atomic.AtomicInteger()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (request.path == "/token") okhttp3.mockwebserver.MockResponse().setBody("token-${tokens.incrementAndGet()}")
                    else if (request.getHeader("Authorization") == "token-${tokens.get()}") normal.dispatch(request)
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(401)
            }
            fixture.source(customize = { raw -> JsonObject(raw +
                ("header" to JsonPrimitive("@js:JSON.stringify({Authorization:java.ajax('/token'),'X-Source':'A'})")))
            }).use { source ->
                val book = source.search("title").single()
                assertEquals(1, tokens.get())
                val chapters = source.directory(book.id)
                source.content(book.id, chapters[1].id)
                source.image(book.id, fixture.server.url("/cover.png").toString(), true)
                assertEquals("Each business request evaluates its header exactly once", fixture.documents.get(), tokens.get())
                val before = tokens.get()
                source.search("title", page = 2)
                assertEquals(before + 1, tokens.get())
            }
        }
    }

    @Test fun nestedRequestsRefreshOneTimeHeadersAndPreserveTheirCallingResult() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val tokens = java.util.concurrent.atomic.AtomicInteger()
            val requests = java.util.concurrent.atomic.AtomicInteger()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.path == "/token") return okhttp3.mockwebserver.MockResponse().setBody("token-${tokens.incrementAndGet()}")
                    if (request.path == "/explicit") return okhttp3.mockwebserver.MockResponse().setBody("explicit")
                    val expected = requests.incrementAndGet()
                    if (request.getHeader("Authorization") != "token-$expected") return okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                    return okhttp3.mockwebserver.MockResponse().setBody(when (request.path) {
                        "/lookup" -> "/search"
                        "/search" -> "<li><a href='/book/one'><h2>Same title</h2></a></li>"
                        else -> "accepted"
                    })
                }
            }
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive("@js:var local='header';var result=java.ajax('/token');JSON.stringify({Authorization:result})"),
                "searchUrl" to JsonPrimitive("@js:java.ajax('/lookup')"),
                "loginCheckJs" to JsonPrimitive("java.connect(result.url())"),
                "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject + ("name" to JsonPrimitive("""
                    h2@text@js:var local='caller';if(java.ajax('/nested')!=='accepted')throw 'nested';
                    if(local!=='caller')throw 'header overwrote caller';
                    if(java.connect('/explicit','{}').body()!=='explicit')throw 'explicit';result
                """.trimIndent())))
            )) }).use { source ->
                assertEquals("Same title", source.search("title").single().title)
                assertEquals(4, requests.get())
                assertEquals(4, tokens.get())
            }
        }
    }

    @Test fun redirectsToNextChapterNeverBecomeCurrentChapterContent() = runBlocking {
        for (firstPage in listOf(false, true)) RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val book = source.search("title").single()
            val chapters = source.directory(book.id)
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (request.path == if (firstPage) "/c/1" else "/c/1b")
                        okhttp3.mockwebserver.MockResponse().setResponseCode(302).addHeader("Location", "/c/2")
                    else normal.dispatch(request)
            }
            if (firstPage) assertEquals(ContentError.EmptyContent, failure { source.content(book.id, chapters[1].id) }.code)
            else {
                val content = source.content(book.id, chapters[1].id)
                assertEquals(chapters[1].id, content.id)
                assertEquals("One", content.title)
                assertNull(content.previous)
                assertEquals(chapters[2].id, content.next)
                assertEquals(listOf("A first", null, "after image", "from-search:One"), content.parts.map { it.text })
            }
            assertEquals("second chapter", source.content(book.id, chapters[2].id).parts.first().text)
            assertEquals(chapters.map { it.id to it.title }, source.directory(book.id).map { it.id to it.title })
        } }
    }

    @Test fun importedRulesReachSearchDirectoryReadingAndImagesThroughRealWorkerAndBroker() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val found = source.search("some title").single()
            assertEquals("Same title", found.title)
            val information = source.information(found.id)
            assertEquals("Same author", information.author)
            val chapters = source.directory(found.id)
            assertEquals(listOf("Volume one", "One", "Two"), chapters.map { it.title })
            assertTrue(chapters.first().isVolume)
            val content = source.content(found.id, chapters[1].id)
            assertEquals(chapters[1].id, content.id)
            assertNull(content.previous); assertEquals(chapters[2].id, content.next)
            assertEquals(listOf("A first", null, "after image", "from-search:One", "last replaced", "from-search:One"), content.parts.map { it.text })
            assertEquals(fixture.server.url("/image.png").toString(), content.parts[1].image)
            assertArrayEquals(byteArrayOf(3, 2, 1), source.image(found.id, information.coverUrl, cover = true))
            assertArrayEquals(byteArrayOf(3, 2, 1), source.image(found.id, content.parts[1].image!!, cover = false))
        } }
    }

    @Test fun sameRemoteBookAcrossSourcesRetainsSeparateVariablesAndContent() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source("A").use { a -> fixture.source("B").use { b ->
            val bookA = a.search("title").single(); val bookB = b.search("title").single()
            assertEquals(bookA.id, bookB.id); assertEquals(bookA.title, bookB.title)
            assertNotEquals(a.definition.sourceId, b.definition.sourceId)
            val chaptersA = a.directory(bookA.id); val chaptersB = b.directory(bookB.id)
            assertEquals(chaptersA[1].id, chaptersB[1].id)
            assertEquals("A first", a.content(bookA.id, chaptersA[1].id).parts.first().text)
            assertEquals("B first", b.content(bookB.id, chaptersB[1].id).parts.first().text)
            a.close()
            assertEquals(ContentError.Unavailable, failure { a.information(bookA.id) }.code)
            assertEquals("Same title", b.information(bookB.id).title)
        } } }
    }

    @Test fun directBookUrlAndMissingSearchUseExplicitCapabilities() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { JsonObject(it - "searchUrl") }).use { source ->
            assertEquals(ContentError.MissingCapability, failure { source.search("title") }.code)
            val id = fixture.server.url("/book/one").toString()
            assertEquals(id, source.information(id).id)
            assertEquals(3, source.directory(id).size)
        } }
    }

    @Test fun cyclesAndHttpFailuresNeverBecomeEmptySuccess() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            fixture.cycle = true
            assertEquals(ContentError.RepeatedPage, failure { source.directory(id) }.code)
            fixture.cycle = false
            assertEquals(3, source.directory(id).size)
            fixture.status = 401
            assertEquals(ContentError.Network, failure { source.information(id) }.code)
        } }
    }

    @Test fun publicHttpDenialsDoNotChangeTheAccountOrRequireLogin() = runBlocking {
        for (withLogin in listOf(false, true)) RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> if (withLogin) JsonObject(raw +
                ("loginUrl" to JsonPrimitive(fixture.server.url("/login").toString()))) else raw
            }).use { source ->
                val definition = source.definition
                val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                    listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                session.write(StorageRequest(StorageArea.Account, "login/status", "session"))
                for (status in listOf(401, 403)) {
                    fixture.status = status
                    assertEquals(ContentError.Network, failure { source.search("title") }.code)
                    assertEquals("session", (session.read(StorageRequest(StorageArea.Account, "login/status")) as StorageResult.Value).value)
                }
                fixture.status = 200
                assertEquals("Same title", source.search("title").single().title)
            }
        }
    }

    @Test fun responseHooksAndSharedCatalogFormattingUsePinnedContracts() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { raw -> JsonObject(raw + mapOf(
            "loginCheckJs" to JsonPrimitive("@js:if (result.code()!==200 || !result.getBody()) throw 'bad response'; result"),
            "coverDecodeJs" to JsonPrimitive("@js:result.reverse()"),
            "ruleToc" to JsonObject(raw.getValue("ruleToc").jsonObject + ("formatJs" to JsonPrimitive("@js:++gInt; index+':'+gInt+':'+title")))
        )) }).use { source ->
            val book = source.search("title").single()
            val chapters = source.directory(book.id)
            assertEquals(listOf("1:1:Volume one", "2:2:One", "3:3:Two"), chapters.map { it.title })
            assertEquals("2:2:One", source.content(book.id, chapters[1].id).title)
            assertArrayEquals(byteArrayOf(3, 2, 1), source.image(book.id, fixture.server.url("/cover.png").toString(), true))
        } }
    }

    @Test fun failedHooksAndEmptyContentAreFailuresAndCannotReplaceStoredCatalog() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { JsonObject(it + ("loginCheckJs" to JsonPrimitive("false"))) }).use { source ->
                assertEquals(ContentError.InvalidRule, failure { source.search("title") }.code)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject { put("content", "missing@html") })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(ContentError.EmptyContent, failure { source.content(book.id, chapter.id) }.code)
                assertEquals(3, source.directory(book.id).size)
            }
        }
    }

    @Test fun linksNormalizeSchemesAndRetainRequestOptions() {
        assertEquals("https://example.test/chapter, {\"method\":\"POST\"}",
            sourceLink("HTTPS://example.test/book, {\"headers\":{}}", "chapter, {\"method\":\"POST\"}"))
        assertThrows(SourceContentException::class.java) { sourceLink("https://example.test/", "javascript:alert(1)") }
    }

    @Test fun matchingBookUrlPatternParsesSearchResponseAsInformationWithoutRefetching() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { JsonObject(it + mapOf(
            "searchUrl" to JsonPrimitive("/book/one"), "bookUrlPattern" to JsonPrimitive("http://.*/book/one")
        )) }).use { source ->
            val book = source.search("title").single()
            assertEquals("Same title", book.title)
            assertEquals(fixture.server.url("/book/one").toString(), book.id)
            assertEquals(1, fixture.documents.get())
        } }
    }

    @Test fun informationInitializationMutatesTheSameBookUsedByLaterFields() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { raw -> JsonObject(raw +
            ("ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                ("init" to JsonPrimitive("@js:book.setName('From initialization');result")))))
        }).use { source ->
            val book = source.search("title").single()
            assertEquals("From initialization", source.information(book.id).title)
        } }
    }

    @Test fun backgroundUpdateMarkerChangesOnlyWhenSourceContentChanges() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            val first = source.information(id)
            assertEquals(first.observedUpdate, source.information(id).observedUpdate)
            fixture.extraChapter = true
            val updated = source.information(id)
            assertTrue(updated.observedUpdate > first.observedUpdate)
            assertEquals(updated.observedUpdate, source.information(id).observedUpdate)
        } }
    }

    @Test fun changingCursorCannotHideRepeatedCatalogPages() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            fixture.duplicateToc = true
            assertEquals(ContentError.RepeatedPage, failure { source.directory(fixture.server.url("/book/one").toString()) }.code)
        } }
    }

    @Test fun decodedImagesUseCompactByteTransport() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            fixture.imageBytes = ByteArray(16000) { (it % 256).toByte() }
            val id = fixture.server.url("/book/one").toString()
            assertArrayEquals(fixture.imageBytes.reversedArray(), source.image(id, fixture.server.url("/image.png").toString(), false))
        } }
    }

    @Test fun revocationAndCancellationDiscardLateRuleWrites() = runBlocking {
        for (cancel in listOf(false, true)) RuleSourceFixture().use { fixture -> fixture.source(customize = { raw ->
            JsonObject(raw + ("ruleContent" to JsonObject(raw.getValue("ruleContent").jsonObject +
                ("title" to JsonPrimitive("@js:chapter.putVariable('chapterKey','pending');'Pending title'")))))
        }).use { source ->
            val id = source.search("title").single().id
            source.directory(id)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task -> if (task is ExecutionTask.ContentMarkup) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            } }
            val operation = async { runCatching { source.content(id, fixture.server.url("/c/1").toString()) } }
            withTimeout(5000) { entered.await() }
            if (cancel) operation.cancel() else source.close()
            release.complete(Unit)
            if (cancel) operation.join() else assertEquals(ContentError.Unavailable, (operation.await().exceptionOrNull() as SourceContentException).code)
            val definition = source.definition
            val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile), listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val saved = session.read(StorageRequest(StorageArea.Config, "content/book/" + digest(id))) as StorageResult.Value
            val record = Json.decodeFromString(BookRecord.serializer(), saved.value!!)
            assertEquals("One", record.chapters[1].title)
            assertEquals("One", record.chapters[1].state.variables["chapterKey"])
        } }
    }

    private suspend fun failure(block: suspend () -> Any): SourceContentException = try {
        block(); throw AssertionError("Expected an explicit content failure")
    } catch (failure: SourceContentException) { failure }
}
