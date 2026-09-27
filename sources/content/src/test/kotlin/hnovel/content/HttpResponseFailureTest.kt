package hnovel.content

import hnovel.execution.ExecutionTask
import hnovel.execution.FailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class HttpResponseFailureTest {
    private fun rules(raw: JsonObject, script: String, hook: String = "") = JsonObject(raw + mapOf(
        "searchUrl" to JsonPrimitive("/list"),
        "exploreUrl" to JsonPrimitive("Books::/list"),
        "loginCheckJs" to JsonPrimitive(hook),
        "ruleSearch" to fields(script), "ruleExplore" to fields(script)
    ))

    private fun fields(script: String) = buildJsonObject {
        put("bookList", script); put("name", "h2@text"); put("bookUrl", "a@href")
    }

    private fun respond(fixture: RuleSourceFixture, status: Int, body: String = """{"error":true,"body":[]}""") {
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(status).setBody(body)
        }
    }

    @Test fun rejectedResponsesDoNotBecomeScriptCompatibilityErrors() = runBlocking {
        for (status in listOf(400, 401, 403, 429, 503)) for (hook in listOf("", "result")) {
            RuleSourceFixture().use { fixture ->
                respond(fixture, status)
                fixture.source { rules(it, "@js:JSON.parse(result).body.page.items", hook) }.use { source ->
                    val search = runCatching { source.search("book") }.exceptionOrNull() as SourceContentException
                    assertEquals("status=$status hook=$hook", ContentError.Network, search.code)
                    assertEquals("ruleSearch.bookList", search.field)
                    assertEquals(status, search.httpStatus)
                    assertEquals(FailureCode.ScriptRuntime, search.diagnostic?.code)
                    val preview = runCatching { source.openDiscovery("http").preview("/list", emptyMap()) }
                        .exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.Network, preview.code)
                    assertEquals("ruleExplore.bookList", preview.field)
                    assertEquals(status, preview.httpStatus)
                }
            }
        }
    }

    @Test fun anEmptyRejectedResponseDoesNotEndPaginationAndCanBeRetried() = runBlocking {
        RuleSourceFixture().use { fixture ->
            respond(fixture, 403)
            fixture.source { rules(it, "<js>try { JSON.parse(result).body.page.items } catch(e) { result.charAt(0)==='<' ? result : [] }</js>li") }.use { source ->
                val pages = source.openDiscovery("http").openPages("/list", emptyMap())
                val failure = runCatching { pages.page(1) }.exceptionOrNull() as? SourceContentException
                assertEquals(ContentError.Network, failure?.code)
                assertEquals(403, failure?.httpStatus)
                respond(fixture, 200, "<li><a href='/book/1'><h2>Recovered</h2></a></li>")
                // The same pager must keep page 1 available after the rejected request.
                assertEquals("Recovered", pages.page(1).books.single().title)
                respond(fixture, 200, "")
                val end = pages.page(2)
                assertTrue(end.books.isEmpty())
                assertNull(end.nextCursor)
            }
        }
    }

    @Test fun failedLoginHooksRetainTheRejectedResponseContext() = runBlocking {
        RuleSourceFixture().use { fixture ->
            respond(fixture, 403)
            fixture.source { rules(it, "li", "if(result.code()!==200)throw 'rejected';result") }.use { source ->
                val failure = runCatching { source.search("book") }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.Network, failure.code)
                assertEquals("loginCheckJs", failure.field)
                assertEquals(403, failure.httpStatus)
            }
        }
    }

    @Test fun successfulEmptyPagesAndBrokenSuccessfulRulesKeepTheirMeaning() = runBlocking {
        RuleSourceFixture().use { fixture ->
            respond(fixture, 200)
            fixture.source { rules(it, "@js:[]") }.use { source ->
                val page = source.openDiscovery("empty").preview("/list", emptyMap())
                assertTrue(page.books.isEmpty())
                assertNull(page.nextCursor)
            }
            fixture.source { rules(it, "@js:JSON.parse(result).body.page.items") }.use { source ->
                val failure = runCatching { source.search("book") }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.InvalidRule, failure.code)
                assertEquals(FailureCode.ScriptRuntime, failure.diagnostic?.code)
                assertNull(failure.httpStatus)
            }
        }
    }

    @Test fun responseHookRetriesUseTheFinalResponseStatus() = runBlocking {
        for (retryStatus in listOf(200, 403)) RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val status = if (request.path == "/retry") retryStatus else if (retryStatus == 200) 403 else 200
                    return MockResponse().setResponseCode(status).setBody(if (status == 200)
                        "<li><a href='/book/1'><h2>Recovered</h2></a></li>" else "denied")
                }
            }
            fixture.source { rules(it, "@js:java.getElements('li')", "java.connect('/retry')") }.use { source ->
                val result = runCatching { source.openSearchPages("book").page(1) }
                if (retryStatus == 200) assertEquals("Recovered", result.getOrThrow().books.single().title)
                else {
                    val failure = result.exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.Network, failure.code)
                    assertEquals(403, failure.httpStatus)
                }
            }
        }
    }

    @Test fun anExplicitContinuationCanRecoverAnEmptyErrorResponse() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/list")
                    MockResponse().setResponseCode(404).setBody("placeholder") else original.dispatch(request)
            }
            fixture.source { raw -> JsonObject(rules(raw, "@js:java.getElements('li')") +
                ("ruleExplore" to JsonObject(fields("@js:java.getElements('li')") +
                    ("nextPageUrl" to JsonPrimitive("@js:result==='placeholder' ? '/search' : ''")))))
            }.use { source ->
                val pages = source.openDiscovery("continuation").openPages("/list", emptyMap())
                val first = pages.page(1)
                assertTrue(first.books.isEmpty())
                assertNotNull(first.nextCursor)
                assertEquals("Same title", pages.page(first.nextCursor).books.single().title)
            }
        }
    }

    @Test fun rejectedResponsesDoNotReplaceSyntaxPermissionOrCancellationFailures() = runBlocking {
        for ((script, expected) in listOf("@js:var = ;" to ContentError.InvalidRule,
            "@js:java.ajax('https://unapproved.invalid/list')" to ContentError.PermissionDenied)) {
            RuleSourceFixture().use { fixture ->
                respond(fixture, 403)
                fixture.source { rules(it, script) }.use { source ->
                    val failure = runCatching { source.search("book") }.exceptionOrNull() as SourceContentException
                    assertEquals(expected, failure.code)
                    assertNull(failure.httpStatus)
                }
            }
        }
        RuleSourceFixture().use { fixture ->
            respond(fixture, 403)
            fixture.beforeRun = { task, _ ->
                if ((task as? ExecutionTask.Rule)?.location?.field == "ruleSearch.bookList") throw CancellationException("cancelled")
            }
            fixture.source { rules(it, "@js:[]") }.use { source ->
                assertTrue(runCatching { source.search("book") }.exceptionOrNull() is CancellationException)
            }
        }
    }

    @Test fun recoveredListsDoNotHideLaterBookFieldErrors() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/list")
                    MockResponse().setResponseCode(404).setBody("placeholder") else original.dispatch(request)
            }
            fixture.source { raw -> JsonObject(rules(raw, "<js>java.ajax('/search')</js>li") +
                ("ruleSearch" to JsonObject(fields("<js>java.ajax('/search')</js>li") +
                    ("name" to JsonPrimitive("@js:throw 'broken title rule'")))))
            }.use { source ->
                val failure = runCatching { source.search("book") }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.InvalidRule, failure.code)
                assertEquals("ruleSearch.name", failure.field)
                assertNull(failure.httpStatus)
            }
        }
    }
}
