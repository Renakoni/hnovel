package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import hnovel.imports.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleSettingsPresentationTest {
    private fun declaration(fields: Map<String, JsonElement>, profile: String = LEGADO_PROFILE) =
        RuleSettingsPresentation.read(SourceDefinition("fixture", "legado", profile, "https://example.invalid/", "Fixture",
            true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, JsonObject(fields).toString()))

    @Test fun absentAndCheckOnlyAndEmptyFormsDoNotInventLogin() {
        for (fields in listOf(emptyMap(), mapOf("loginCheckJs" to JsonPrimitive("@js:throw 'never run'")),
            mapOf("loginUi" to JsonPrimitive("[]")))) {
            val state = declaration(fields + ("variableComment" to JsonPrimitive("Author description only")))
            assertFalse(state.loginDeclared)
            assertNull(state.loginErrorField)
            assertEquals("Author description only", state.variableDescription)
        }
    }

    @Test fun staticFormsAndBrowserDeclarationsUseExistingFormSemantics() {
        val form = declaration(mapOf("loginUi" to JsonPrimitive("""[{"name":"account"},{"name":"action","type":"button","action":"doWork()"}]""")))
        assertTrue(form.loginDeclared)
        assertNull(form.loginErrorField)
        val browser = declaration(mapOf("loginUrl" to JsonPrimitive("https://example.invalid/login"), "browserRead" to JsonPrimitive(true)))
        assertTrue(browser.loginDeclared)
        assertTrue(browser.nativeBrowser)
        assertNull(browser.loginErrorField)
    }

    @Test fun invalidDeclarationsHaveFieldLocationsAndDynamicFormsStayUnevaluated() {
        for (ui in listOf("{}", "not a form", "https://example.invalid/login")) {
            assertEquals("loginUi", declaration(mapOf("loginUi" to JsonPrimitive(ui))).loginErrorField)
        }
        val unknown = """[{"name":"account","type":"unknown"}]"""
        assertEquals("loginUi[0].type", declaration(mapOf("loginUi" to JsonPrimitive(unknown))).loginErrorField)
        val dynamic = mapOf("loginUi" to JsonPrimitive("@js:throw 'this cannot run while opening settings'"))
        assertEquals("loginUi", declaration(dynamic).loginErrorField)
        assertNull(declaration(dynamic, EXTENSION_PROFILE).loginErrorField)
        assertTrue(declaration(dynamic, EXTENSION_PROFILE).loginDeclared)
    }
}
