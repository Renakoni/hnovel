package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleEnvironmentTest {
    @Test fun queriedUserAgentMatchesDefaultSourceHeadersAndRequestOverrides() = runBlocking {
        for ((header, option, expected) in listOf(
            Triple("", "", DESKTOP_USER_AGENT),
            Triple("{\"uSeR-aGeNt\":\"Configured-UA\"}", "", "Configured-UA"),
            Triple("@js:JSON.stringify({'User-Agent':java.getUserAgent()+' custom'})", "", "$DESKTOP_USER_AGENT custom"),
            Triple("{\"User-Agent\":\"Source-UA\"}", ",{\"headers\":{\"User-Agent\":\"Request-UA\"}}", "Request-UA")
        )) RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive(header),
                "searchUrl" to JsonPrimitive("/search$option"),
                "loginCheckJs" to JsonPrimitive("if(java.getUserAgent()!==${JsonPrimitive(expected)}) throw 'wrong UA'; result;"),
                "ruleSearch" to buildJsonObject {
                    put("bookList", "li"); put("bookUrl", "a@href"); put("name", "@js:java.getUserAgent()")
                }
            )) }.use { source ->
                assertEquals(expected, source.search("test").single().title)
                assertEquals(expected, fixture.server.takeRequest().getHeader("User-Agent"))
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun browserDefaultIsDeviceProvidedWhileHttpQueriesReflectSourceConfiguration() = runBlocking {
        val browserUa = "Device WebView-UA"
        val seen = mutableListOf<String?>()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = browserUa
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                seen += request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                return BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(), "<li><a href='/book'><h2>Book</h2></a><b>Author</b></li>".toByteArray(),
                    "UTF-8", 0, kind = ResponseKind.BrowserDocument))
            }
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "browserRead" to JsonPrimitive(true), "header" to JsonPrimitive("{\"User-Agent\":\"Browser-Override\"}"),
                "loginCheckJs" to JsonPrimitive("""
                    if(java.getWebViewUA()!==${JsonPrimitive(browserUa)} || java.getUserAgent()!=='Browser-Override') throw 'UA'; result;
                """.trimIndent())
            )) }.use { source ->
                assertEquals(1, source.search("test").size)
                assertEquals(listOf("Browser-Override"), seen)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun queryingResponseUserAgentDoesNotExecuteTheHeaderScriptAgain() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive("""@js:
                    var count=Number(source.get('headerCalls')||0)+1; source.put('headerCalls',String(count));
                    JSON.stringify({'User-Agent':'Header-'+count});
                """.trimIndent()),
                "loginCheckJs" to JsonPrimitive("""
                    if(java.getUserAgent()!=='Header-1' || java.getUserAgent()!=='Header-1' || source.get('headerCalls')!=='1') throw 'header ran again'; result;
                """.trimIndent())
            )) }.use { source ->
                assertEquals(1, source.search("key").size)
                assertEquals("Header-1", fixture.server.takeRequest().getHeader("User-Agent"))
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun metadataIsReadOnlyAndComesFromEachRevisionWithNumericMissingTime() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for ((name, time) in listOf("First" to JsonPrimitive(1700000000000L), "Updated" to JsonPrimitive(1800000000000L),
                "Missing" to JsonNull)) {
                val expectedTime = if (time == JsonNull) JsonPrimitive(0) else time
                fixture.source { raw -> JsonObject(raw + mapOf(
                    "bookSourceName" to JsonPrimitive(name), "lastUpdateTime" to time,
                    "ruleSearch" to buildJsonObject {
                        put("bookList", "li"); put("bookUrl", "a@href"); put("name", """
                            @js:try { source.bookSourceName='fake'; source.lastUpdateTime=99; } catch(e) {}
                            try { Object.defineProperty(source,'bookSourceName',{value:'fake'}); } catch(e) {}
                            if(source.bookSourceName!==source.getBookSourceName() || typeof source.lastUpdateTime!=='number') throw 'metadata';
                            JSON.stringify([source.bookSourceName,source.getLastUpdateTime()]);
                        """.trimIndent())
                    }
                )) }.use { source ->
                    assertEquals(JsonArray(listOf(JsonPrimitive(name), expectedTime)).toString(), source.search("test").single().title)
                }
            }
        }
    }
}
