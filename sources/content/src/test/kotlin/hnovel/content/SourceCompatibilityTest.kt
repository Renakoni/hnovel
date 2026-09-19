package hnovel.content

import hnovel.imports.EXTENSION_PROFILE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceCompatibilityTest {
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
                "jsLib" to JsonPrimitive("new JavaImporter();"),
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
                    assertEquals(hnovel.rules.ScriptDependency.JavaImporter, failure.dependency)
                    assertNull(failure.denial)
                }
                assertEquals(0, fixture.documents.get())
            }
        }
    }
}
