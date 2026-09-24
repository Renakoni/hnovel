package hnovel.content

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class RuleCanonicalBookTest {
    @Test fun singleWorksAndSeriesResolveToOneBookAndStableChapters() = runBlocking {
        RuleSourceFixture().use { fixture ->
            seriesResponses(fixture)
            seriesSource(fixture).use { source ->
                val series = fixture.server.url("/series/10").toString()
                val singles = listOf("/novel/1", "/novel/2").map { fixture.server.url(it).toString() }
                val books = (singles + series).map { id -> async { source.information(id) } }.awaitAll()
                assertEquals(listOf(series, series, series), books.map { it.id })
                for (id in singles + series) {
                    assertEquals(singles, source.directory(id).map { it.id })
                    assertEquals("second chapter", source.content(id, singles[1]).parts.single().text)
                    assertEquals(series, source.information(id).id)
                }
            }
        }
    }

    @Test fun anotherSourceCannotInheritTheAliasAndStandaloneWorksStayStandalone() = runBlocking {
        RuleSourceFixture().use { fixture ->
            seriesResponses(fixture)
            val single = fixture.server.url("/novel/1").toString()
            seriesSource(fixture).use { series ->
                assertEquals(fixture.server.url("/series/10").toString(), series.information(single).id)
                seriesSource(fixture, label = "B", canonicalize = false).use { standalone ->
                    assertEquals(single, standalone.information(single).id)
                    assertNotEquals(series.definition.sourceId, standalone.definition.sourceId)
                }
            }
        }
    }

    @Test fun inaccessibleSeriesDoesNotCommitAnAlias() = runBlocking {
        RuleSourceFixture().use { fixture ->
            seriesResponses(fixture, seriesStatus = 404)
            seriesSource(fixture).use { source ->
                val id = fixture.server.url("/novel/1").toString()
                try {
                    source.information(id)
                    fail("A canonical identity requires a readable catalogue")
                } catch (_: SourceContentException) {
                    // A failed conversion must not make the previously reachable work an alias.
                }
                seriesResponses(fixture)
                assertEquals(fixture.server.url("/series/10").toString(), source.information(id).id)
            }
        }
    }

    private fun seriesSource(fixture: RuleSourceFixture, label: String = "A", canonicalize: Boolean = true) =
        fixture.source(label) { raw ->
            JsonObject(raw + mapOf(
                "ruleBookInfo" to buildJsonObject {
                    if (canonicalize) put("init", "@js:book.bookUrl = '${fixture.server.url("/series/10")}'; result")
                    put("name", "h1@text")
                    put("tocUrl", "@js:book.bookUrl")
                    put("updateTime", "@js:'published'")
                },
                "ruleToc" to buildJsonObject {
                    put("chapterList", "li"); put("chapterName", "a@text"); put("chapterUrl", "a@href")
                },
                "ruleContent" to buildJsonObject { put("content", "article@text") },
            ))
        }

    private fun seriesResponses(fixture: RuleSourceFixture, seriesStatus: Int = 200) {
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/series/10" && seriesStatus != 200)
                    return MockResponse().setResponseCode(seriesStatus)
                val body = when (request.path) {
                    "/novel/1" -> "<h1>First work</h1><article>first chapter</article>"
                    "/novel/2" -> "<h1>Second work</h1><article>second chapter</article>"
                    "/series/10" -> "<h1>Series</h1><li><a href='/novel/1'>First</a></li><li><a href='/novel/2'>Second</a></li>"
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "text/html").setBody(body)
            }
        }
    }
}
