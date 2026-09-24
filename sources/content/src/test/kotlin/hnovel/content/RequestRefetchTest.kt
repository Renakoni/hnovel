package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RequestRefetchTest {
    @Test fun explicitRefetchUsesUpdatedCookiesAndDoesNotReenterLoginCheck() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("X-Session", "new")
                    .setBody("<li><a href='/book'><h2>${if (request.getHeader("Cookie") == "session=new") "New" else "Old"}</h2></a></li>")
            }
            fixture.source { raw -> JsonObject(raw + ("loginCheckJs" to JsonPrimitive("""
                if(source.get('checks')) throw 'recursive loginCheck';
                source.put('checks','1');
                cookie.replaceCookie(result.getUrl(),'session=new');
                var next=java.getStrResponse(null,null);
                if(next.code()!==200 || next.header('X-Session')!=='new' || next.getUrl()!==result.getUrl()) throw 'response metadata';
                next;
            """.trimIndent()))) }.use { source ->
                assertEquals("New", source.search("key").single().title)
                assertEquals(2, fixture.server.requestCount)
                val initial = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
                val repeated = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(initial.path, repeated.path)
                assertNull(initial.getHeader("Cookie"))
                assertEquals("session=new", repeated.getHeader("Cookie"))
            }
        }
    }

    @Test fun noRefetchMeansOneRequestAndExplicitPostCallsPreserveBodyAndHeaders() = runBlocking {
        for ((hook, count) in listOf("result" to 1, "java.getStrResponse(null,null);java.getStrResponse(null,null)" to 3)) {
            RuleSourceFixture().use { fixture ->
                fixture.source { raw -> JsonObject(raw + mapOf(
                    "searchUrl" to JsonPrimitive("""/search,{"method":"POST","body":"query={{key}}","headers":{"X-Synthetic":"kept","Content-Type":"application/x-www-form-urlencoded"}}"""),
                    "loginCheckJs" to JsonPrimitive(hook)
                )) }.use { source ->
                    assertEquals(1, source.search("one two").size)
                    assertEquals(count, fixture.server.requestCount)
                    val requests = (1..count).map { fixture.server.takeRequest(1, TimeUnit.SECONDS)!! }
                    assertTrue(requests.all { it.method == "POST" && it.getHeader("X-Synthetic") == "kept" })
                    assertEquals(listOf("query=one+two"), requests.map { it.body.readUtf8() }.distinct())
                }
            }
        }
    }

    @Test fun rulesOutsideLoginCheckHaveNoCurrentRequest() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "loginCheckJs" to JsonPrimitive("result"),
                "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject + ("name" to JsonPrimitive("h2@text@js:java.getStrResponse(null,null).body()")))
            )) }.use { source ->
                assertTrue(runCatching { source.search("key") }.isFailure)
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun refetchRestartsAtOriginalUrlAndRetainsRedirectPolicy() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/entry")
                    MockResponse().setResponseCode(302).setHeader("Location", "/final")
                else MockResponse().setBody("<li><a href='/book'><h2>Final</h2></a></li>")
            }
            fixture.source { raw -> JsonObject(raw + mapOf(
                "searchUrl" to JsonPrimitive("/entry"),
                "loginCheckJs" to JsonPrimitive("""
                    var next=java.getStrResponse(null,null);
                    if(next.code()!==200 || !next.getUrl().endsWith('/final')) throw 'redirect'; next;
                """.trimIndent())
            )) }.use { source ->
                assertEquals("Final", source.search("key").single().title)
                assertEquals(4, fixture.server.requestCount)
                assertEquals(listOf("/entry", "/final", "/entry", "/final"), (1..4).map { fixture.server.takeRequest().path })
            }
        }
    }
}
