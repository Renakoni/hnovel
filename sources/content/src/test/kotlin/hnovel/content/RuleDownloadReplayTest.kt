package hnovel.content

import hnovel.network.RequestRetryContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleDownloadReplayTest {
    private fun declarative(raw: JsonObject) = JsonObject(raw - "coverDecodeJs" + mapOf(
        "ruleBookInfo" to buildJsonObject { put("name", "h1@text"); put("tocUrl", "a.toc@href"); put("updateTime", "h1@text") },
        "ruleToc" to buildJsonObject { put("chapterList", "li"); put("chapterName", "a@text"); put("chapterUrl", "a@href") },
        "ruleContent" to buildJsonObject { put("content", "article@html") },
    ))

    @Test fun wholeDownloadReplayExcludesHooksLibrariesAndMutableScriptState() {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = ::declarative).use { assertTrue(it.canReplayDownloads) }
            for ((field, value) in listOf(
                "browserRead" to JsonPrimitive(true), "jsLib" to JsonPrimitive("var state=0"),
                "loginCheckJs" to JsonPrimitive("result"), "coverDecodeJs" to JsonPrimitive("result"),
                "header" to JsonPrimitive("@js:JSON.stringify({})"),
                "ruleBookInfo" to buildJsonObject { put("init", "h1"); put("name", "h1@text") },
                "ruleToc" to buildJsonObject { put("formatJs", "result") },
                "ruleContent" to buildJsonObject { put("content", "article@html@get:{key}") },
                "ruleContent" to buildJsonObject { put("imageDecode", "result") },
            )) fixture.source { JsonObject(declarative(it) + (field to value)) }.use { assertFalse(field, it.canReplayDownloads) }
        }
    }

    @Test fun dynamicRequestOptionsDisallowReplayBeforeTheirScriptExecutes() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = ::declarative).use { source ->
                val retry = RequestRetryContext()
                // This is an extracted URL, so it is absent from the static source definition.
                val url = fixture.server.url("/book/one").toString() + ",{js:'result'}"
                withContext(retry) { source.information(url) }
                assertFalse(retry.replaySafe)
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun transientAndPermanentResponsesKeepDifferentRecoveryHints() = runBlocking {
        for (status in listOf(429, 502, 503, 504, 404, 401)) RuleSourceFixture().use { fixture ->
            fixture.status = status
            fixture.source(customize = ::declarative).use { source ->
                val failure = runCatching { withContext(RequestRetryContext()) {
                    source.information(fixture.server.url("/book/one").toString())
                } }.exceptionOrNull() as SourceContentException
                assertEquals(status, failure.httpStatus)
                assertEquals(status in setOf(429, 502, 503, 504), failure.retry != null)
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }
}
