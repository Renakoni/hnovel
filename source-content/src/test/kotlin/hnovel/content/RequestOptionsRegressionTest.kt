package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RequestOptionsRegressionTest {
    @Test fun searchAndNestedConnectSubmitEquivalentSingleQuotedRequests() = runBlocking {
        for (dynamic in listOf(false, true)) RuleSourceFixture().use { fixture ->
            val rule = """/search,{'method':'POST','charset':'GB2312','body':'keyword={{key}}','header':{'X-Test':'it\'s "quoted"'}}"""
            val header = "{'X-Source':'A','X-Inherited':'source header'}"
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "searchUrl" to JsonPrimitive(rule),
                "header" to JsonPrimitive(if (dynamic) "@js:${JsonPrimitive(header)}" else header),
                "loginCheckJs" to JsonPrimitive("java.connect(${JsonPrimitive(rule)})")
            )) }).use { source ->
                assertEquals("Same title", source.search("校园&role=admin").single().title)
                assertEquals(2, fixture.server.requestCount)
                repeat(2) {
                    val request = fixture.server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("POST", request.method)
                    assertEquals("keyword=%D0%A3%D4%B0%26role%3Dadmin", request.body.readUtf8())
                    assertEquals("it's \"quoted\"", request.getHeader("X-Test"))
                    assertEquals("source header", request.getHeader("X-Inherited"))
                }
            }
        }
    }

    @Test fun invalidSourceHeadersIdentifyTheHeaderFieldWithoutNetworkRequests() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { JsonObject(it + ("header" to JsonPrimitive("{'X-Test':'unterminated}"))) }).use { source ->
                val failure = runCatching { source.search("title") }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.InvalidRule, failure.code)
                assertEquals("header", failure.field)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun invalidOptionsKeepTheirRuleFieldAndDifferFromMissingNetworkPermission() = runBlocking {
        for ((rule, expected) in listOf(
            "/search,{'body':'unterminated}" to ContentError.InvalidRule,
            "https://not-granted.invalid/search" to ContentError.PermissionDenied
        )) RuleSourceFixture().use { fixture ->
            fixture.source(customize = { JsonObject(it + ("searchUrl" to JsonPrimitive(rule))) }).use { source ->
                val failure = runCatching { source.search("title") }.exceptionOrNull() as SourceContentException
                assertEquals(expected, failure.code)
                assertEquals("searchUrl", failure.field)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }
}
