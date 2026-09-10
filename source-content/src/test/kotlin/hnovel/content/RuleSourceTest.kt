package hnovel.content

import hnovel.execution.*
import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleSourceTest {

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
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (request.path == "/token") okhttp3.mockwebserver.MockResponse().setBody("source-token")
                    else if (request.getHeader("Authorization") == "source-token") normal.dispatch(request)
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(401)
            }
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive("@js:JSON.stringify({Authorization:java.ajax('/token')})"),
                "loginCheckJs" to JsonPrimitive("java.connect(result.url())")
            )) }).use { source ->
                assertEquals("Same title", source.search("title").single().title)
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

    @Test fun cyclesAndAuthenticationFailuresNeverBecomeEmptySuccess() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            fixture.cycle = true
            assertEquals(ContentError.RepeatedPage, failure { source.directory(id) }.code)
            fixture.cycle = false
            assertEquals(3, source.directory(id).size)
            fixture.status = 401
            assertEquals(ContentError.LoginRequired, failure { source.information(id) }.code)
        } }
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
        for (cancel in listOf(false, true)) RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = source.search("title").single().id
            source.directory(id)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task -> if (task is ExecutionTask.Rule && task.location.field == "ruleContent.parts") {
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
