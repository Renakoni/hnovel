package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Synthetic data shapes from the field inventory; no credentials or copied site scripts. */
class LoginFormDataTest {
    @Test fun strictAndGsonStyleArraysBuildTheSameForm() {
        val strict = """[{"name":"account","type":"text"},{"name":"secret","type":"password"},
            {"name":"sign in","type":"button","action":"login()","style":{"layout_flexGrow":1}}]"""
        val lenient = """[{name:'account',type:'text'},{name:'secret',type:'password'},
            {name:'sign in',type:'button',action:'login()',style:{layout_flexGrow:1}}]"""
        assertEquals(LoginForm.parse(strict, ""), LoginForm.parse(lenient, ""))
        assertEquals("sign in", LoginForm.parse(lenient, "").fields.last().name)
    }

    @Test fun escapedQuotesAndNestedStyleStayData() {
        val form = LoginForm.parse("""[{name:'reader\'s account',style:{extra:[{label:'text'}]}},
            {name:'action',type:'button',action:'source.put(\'key\',\'value\')'}]""", "")
        assertEquals("reader's account", form.fields.first().name)
        assertEquals("source.put('key','value')", form.fields.last().action)
    }

    @Test fun malformedOrNonArrayDataHasATypedRedactedLocation() {
        for (ui in listOf("[{name:'unfinished'}", "[{name:'a'}] trailing", "{}", "null", "false", "'not a form'", "https://example.invalid/account",
            "[{name:(function(){return 'a'})()}]", "[{name:'${"x".repeat(65536)}'}]",
            "[".repeat(65) + "]".repeat(65), " ".repeat(65537))) {
            val failure = runCatching { LoginForm.parse(ui, "") }.exceptionOrNull()
            assertTrue(failure.toString(), failure is SourceContentException)
            assertEquals(ContentError.InvalidRule, (failure as SourceContentException).code)
            assertEquals("loginUi", failure.field)
            assertFalse(failure.toString().contains("unfinished"))
        }
    }

    @Test fun lenientRowsStillEnforceSemanticAndProfileBoundaries() {
        for ((ui, field) in listOf(
            "[{name:'a',unknown:true}]" to "loginUi[0].unknown",
            "[{name:'a',type:'webview'}]" to "loginUi[0].type",
            "[{name:12}]" to "loginUi[0].name",
            "[{name:'a'},{name:'a'}]" to "loginUi.name",
            "[{name:'region',type:'select',chars:[]}]" to "loginUi[0].chars",
            "[{name:'region',type:'select',chars:['east','east']}]" to "loginUi[0].chars",
            "[{name:'region',type:'select',chars:['east'],default:'west'}]" to "loginUi[0].default"
        )) {
            val failure = runCatching { LoginForm.parse(ui, "", extended = true) }.exceptionOrNull() as SourceContentException
            assertEquals(ContentError.InvalidRule, failure.code)
            assertEquals(field, failure.field)
        }
        val extension = "[{name:'region',type:'select',chars:['east','west'],default:'west',viewName:'label()'}]"
        assertTrue(runCatching { LoginForm.parse(extension, "") }.exceptionOrNull() is SourceContentException)
        val form = LoginForm.parse(extension, "", extended = true)
        assertEquals("west", form.values["region"])
        assertEquals("label()", form.fields.single().viewName)
        assertTrue(runCatching { form.validate(mapOf("region" to "outside")) }.isFailure)
        val oversized = List(33) { "{name:'field$it'}" }.joinToString(",", "[", "]")
        assertEquals(ContentError.Limit, (runCatching { LoginForm.parse(oversized, "") }.exceptionOrNull() as SourceContentException).code)
    }

    @Test fun staticLenientFormDoesNotStartTheRuleInterpreter() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.afterRun = { error("Static loginUi is data") }
            fixture.source { raw -> JsonObject(raw + ("loginUi" to JsonPrimitive("[{name:'account'},{name:'secret',type:'password'}]"))) }.use { source ->
                assertEquals(listOf("account", "secret"), source.loginForm().fields.map { it.name })
                assertEquals(0, fixture.documents.get())
            }
        }
    }
}
