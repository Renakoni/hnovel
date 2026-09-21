package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class LongCatalogueTest {
    @Test fun legacySnapshotsRemainReadableAndMoveOnlyAfterASuccessfulWrite() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val id = fixture.server.url("/book/one").toString()
            val definition = fixture.source().use { source ->
                source.search("title")
                source.directory(id)
                source.definition
            }
            val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val key = "content/book/" + digest(id)
            val snapshot = session.read(StorageRequest(StorageArea.BookState, key)) as StorageResult.Value
            assertNotNull(snapshot.value)
            assertEquals(snapshot, session.write(StorageRequest(StorageArea.Config, key, snapshot.value)))
            session.write(StorageRequest(StorageArea.BookState, key))
            fixture.source().use { source ->
                val requests = fixture.server.requestCount
                val content = source.content(id, fixture.server.url("/c/2").toString())
                assertEquals(requests + 1, fixture.server.requestCount)
                assertTrue(content.parts.any { it.text == "from-search:Two" })
            }
            assertNotNull((session.read(StorageRequest(StorageArea.BookState, key)) as StorageResult.Value).value)
            assertEquals(StorageResult.Value(null), session.read(StorageRequest(StorageArea.Config, key)))
        }
    }

    @Test fun aCatalogueCanContinueBeyondSixtyFourPages() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rules = catalogue(fixture, chapters = 69, perPage = 1)
            fixture.source(customize = rules).use { source ->
                val chapters = source.directory(fixture.server.url("/book/one").toString())
                assertEquals(69, chapters.size)
                assertEquals("Chapter 69", chapters.last().title)
                assertEquals(fixture.server.url("/c/69").toString(), chapters.last().id)
            }
        }
    }

    @Test fun tenThousandChaptersKeepTheirOrderStateAndReadingAfterReopening() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rules = catalogue(fixture, chapters = 12001, perPage = 100)
            val bookId = fixture.server.url("/book/one").toString()
            val lastId = fixture.server.url("/c/12001").toString()
            fixture.source(customize = rules).use { source ->
                // The information fallback also loads a full catalogue when no update marker exists.
                source.information(bookId)
                val requests = fixture.server.requestCount
                val chapters = source.directory(bookId)
                assertEquals(requests, fixture.server.requestCount)
                assertEquals((1..12001).map { "Chapter $it" }, chapters.map { it.title })
                assertEquals(12001, chapters.map { it.id }.distinct().size)
                assertEquals("Chapter 12001", chapters.last().state.variables["chapterKey"])
                assertEquals(12000, chapters.last().state.metadata["index"]!!.jsonPrimitive.int)
            }
            fixture.source(customize = rules).use { reopened ->
                val requests = fixture.server.requestCount
                val content = reopened.content(bookId, lastId)
                assertEquals(requests + 1, fixture.server.requestCount)
                assertEquals(fixture.server.url("/c/12000").toString(), content.previous)
                assertNull(content.next)
                assertTrue(content.parts.any { it.text == "Body 12001" })
                assertTrue(content.parts.any { it.text == "Chapter 12001" })
            }
        }
    }

    private fun catalogue(fixture: RuleSourceFixture, chapters: Int, perPage: Int): (JsonObject) -> JsonObject {
        val original = fixture.server.dispatcher
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/c/")) return MockResponse().setBody("<article><p>Body ${path.substringAfterLast('/')}</p></article>")
                if (!path.startsWith("/toc/")) return original.dispatch(request)
                val page = path.substringAfterLast('/').toInt()
                val start = (page - 1) * perPage + 1
                return MockResponse().setBody(buildString {
                    for (index in start..minOf(chapters, start + perPage - 1))
                        append("<li data-updated='today'><a href='/c/$index'>Chapter $index</a></li>")
                    if (start + perPage <= chapters) append("<a class='next' href='/toc/${page + 1}'>next</a>")
                })
            }
        }
        return { raw -> JsonObject(raw + mapOf(
            "ruleToc" to buildJsonObject {
                put("chapterList", "li")
                put("chapterName", "a@text@js:chapter.putVariable('chapterKey',result);result")
                put("chapterUrl", "a@href")
                put("updateTime", "@data-updated")
                put("isVolume", "@data-volume")
                put("isVip", "@data-vip")
                put("isPay", "@data-pay")
                put("formatJs", "title")
                put("nextTocUrl", "a.next@href")
            },
            "ruleContent" to buildJsonObject {
                put("content", "article@html@js:result+'<p>'+chapter.getVariable('chapterKey')+'</p>'")
            }
        )) }
    }
}
