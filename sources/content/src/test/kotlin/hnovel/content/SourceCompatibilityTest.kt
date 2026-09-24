package hnovel.content

import hnovel.imports.EXTENSION_PROFILE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class SourceCompatibilityTest {
    @Test fun redirectedDetailsKeepSearchIdentityNamesAndDefaultDirectory() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                    "/search" -> MockResponse().setBody("<li><a href='/book/one'><h2>Search title</h2></a><b>Search author</b></li>")
                    "/book/one" -> MockResponse().setResponseCode(302).setHeader("Location", "/landing")
                    "/landing" -> MockResponse().setBody("<h1>Detail title</h1><b>Detail author</b><li><a href='/chapter'>One</a></li>")
                    "/chapter" -> MockResponse().setBody("<article>Readable chapter</article>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            fixture.source { raw -> JsonObject(raw + mapOf(
                "ruleBookInfo" to buildJsonObject {
                    put("name", "h1@text"); put("author", "b@text"); put("updateTime", "@js:'published'")
                },
                "ruleContent" to buildJsonObject { put("content", "article@text") },
            )) }.use { source ->
                val searched = source.search("fixture").single()
                repeat(2) {
                    val book = source.information(searched.id)
                    assertEquals(searched.id, book.id)
                    assertEquals(searched.id, source.canonicalBookId(searched.id))
                    assertEquals("Search title", book.title)
                    assertEquals("Search author", book.author)
                    val chapter = source.directory(searched.id).single { !it.isVolume }
                    assertEquals(fixture.server.url("/chapter").toString(), chapter.id)
                    assertEquals("Readable chapter", source.content(searched.id, chapter.id).parts.single().text)
                }
            }
        }
    }

    @Test fun requestBackedBookKeysKeepPostBodyAndDefaultDirectoryWithoutCreatingAliases() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/book" -> if (request.method == "POST" && request.body.readUtf8() == "id=42")
                        MockResponse().setBody("<h1>POST book</h1><li><a href='/chapter'>One</a></li>")
                    else MockResponse().setResponseCode(400)
                    "/chapter" -> MockResponse().setBody("<article>POST source chapter</article>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            fixture.source { raw -> JsonObject(raw + mapOf(
                "ruleBookInfo" to buildJsonObject { put("name", "h1@text"); put("updateTime", "@js:'published'") },
                "ruleContent" to buildJsonObject { put("content", "article@text") },
            )) }.use { source ->
                val id = fixture.server.url("/book").toString() + ",{\"method\":\"POST\",\"body\":\"id=42\"}"
                repeat(2) {
                    assertEquals(id, source.information(id).id)
                    assertEquals(id, source.canonicalBookId(id))
                    val chapter = source.directory(id).single { !it.isVolume }
                    assertEquals("POST source chapter", source.content(id, chapter.id).parts.single().text)
                }
            }
        }
    }

    @Test fun searchCanExplicitlyEvaluateItsOwnLoginPrelude() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "loginUrl" to JsonPrimitive("var queryPrefix='configured';"),
                "searchUrl" to JsonPrimitive("@js:eval(String(source.loginUrl));'/search?q='+queryPrefix")
            )) }.use { source ->
                assertEquals(1, source.search("unused").size)
                assertEquals("/search?q=configured", fixture.server.takeRequest().path)
            }
        }
    }

    @Test fun unavailableLibraryDependencyHasAnActionableFailureAcrossEntryPoints() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + mapOf(
                "jsLib" to JsonPrimitive("new JavaAdapter();"),
                "searchUrl" to JsonPrimitive("@js:'/search'"),
                "exploreUrl" to JsonPrimitive("@js:JSON.stringify([{title:'Browse',url:'/search'}])"),
                "loginUi" to JsonPrimitive("@js:JSON.stringify([{name:'user',type:'text'}])")
            )) }.use { source ->
                for (operation in listOf<suspend () -> Any>(
                    { source.search("fixture") }, { source.openDiscovery("fixture").catalog() }, { source.loginForm() }
                )) {
                    val failure = runCatching { operation() }.exceptionOrNull() as SourceContentException
                    assertEquals("UnsupportedDependency", failure.code.name)
                    assertEquals("jsLib", failure.field)
                    assertEquals(hnovel.rules.ScriptDependency.JavaAdapter, failure.dependency)
                    assertNull(failure.denial)
                }
                assertEquals(0, fixture.documents.get())
            }
        }
    }
}
