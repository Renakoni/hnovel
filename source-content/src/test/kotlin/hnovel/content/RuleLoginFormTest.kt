package hnovel.content

import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.BrowserExecutor
import hnovel.network.BrokerResult
import hnovel.network.FailureCode
import hnovel.network.RequestStage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleLoginFormTest {
    @Test fun directBrowserLoginPreservesBrokerFailures() = runBlocking {
        var code = FailureCode.Network
        val browser = BrowserExecutor { _, _, options, _ ->
            assertTrue(options.interactive)
            BrokerResult.Failure(RequestStage.Connect, code)
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.source { raw -> JsonObject(raw +
                ("loginUrl" to JsonPrimitive(fixture.server.url("/login").toString()))) }.use { source ->
                for ((failureCode, expected) in listOf(
                    FailureCode.OriginDenied to ContentError.PermissionDenied,
                    FailureCode.AddressDenied to ContentError.AddressDenied,
                    FailureCode.Dns to ContentError.Dns,
                    FailureCode.Network to ContentError.Network,
                    FailureCode.Timeout to ContentError.Limit,
                    FailureCode.BrowserRequired to ContentError.BrowserRequired,
                )) {
                    code = failureCode
                    val failure = runCatching { source.login(emptyMap()) }.exceptionOrNull() as SourceContentException
                    assertEquals(failureCode.name, expected, failure.code)
                    assertEquals("loginUrl", failure.field)
                }
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun browserFieldActionsKeepTheirTargetAndReportAuthenticationFailures() = runBlocking {
        val paths = mutableListOf<String>()
        val browser = BrowserExecutor { session, request, options, guard ->
            assertTrue(options.interactive)
            paths += java.net.URI(request.url).path
            session.execute(request.copy(browser = null), guard)
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.status = 401
            for (scripted in listOf(false, true)) {
                val target = fixture.server.url("/verify").toString()
                val action = if (scripted) "java.showBrowser('$target');java.upLoginData({user:'changed'});" else target
                fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + mapOf(
                    "loginUrl" to JsonPrimitive(fixture.server.url("/login").toString()),
                    "loginUi" to JsonPrimitive(buildJsonArray {
                        add(buildJsonObject { put("name", "user"); put("default", "before") })
                        add(buildJsonObject { put("name", "verify"); put("type", "button"); put("action", action) })
                    }.toString())
                )) }.use { source ->
                    val form = source.loginForm()
                    val failure = runCatching { source.login(form.values, "verify") }.exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.LoginRequired, failure.code)
                    assertEquals(if (scripted) "loginUi.action.browser" else "loginUi.action", failure.field)
                    assertEquals("before", source.loginForm().values["user"])
                }
            }
            assertEquals(listOf("/verify", "/verify"), paths)
        }
    }

    @Test fun extensionFormUsesLoginHelpersDefaultsAndItsOwnActionDraft() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + mapOf(
                "loginUrl" to JsonPrimitive("""
                    function label(){return 'Account';}
                    function login(){source.put('submitted',JSON.stringify(result));}
                """.trimIndent()),
                "loginUi" to JsonPrimitive("""@js:
                    source.put('forms',String(Number(source.get('forms')||0)+1));
                    JSON.stringify([
                      {name:'user',default:'alice',viewName:'label()'},
                      {name:'password',type:'password'},
                      {name:'region',type:'select',chars:['east','west'],default:'west'},
                      {name:'remember',type:'toggle',chars:['no','yes']},
                      {name:'change',type:'button',action:"java.upLoginData({user:'bob'});java.reLoginView();"}
                    ]);
                """.trimIndent())
            )) }.use { source ->
                val form = source.loginForm()
                assertEquals("Account", form.fields.first().label)
                assertEquals(mapOf("user" to "alice", "password" to "", "region" to "west", "remember" to "no"), form.values)
                source.login(form.values, "change")
                val changed = source.loginForm()
                assertEquals("bob", changed.values["user"])
                source.login(changed.values)
                // Form scripts are not rerun on submission, only on an explicit refresh.
                val context = source.evaluation()
                assertEquals("2", context.script("source.get('forms')", hnovel.rules.RuleValue.Empty, "fixture").text())
                val submitted = Json.parseToJsonElement(context.script("source.get('submitted')", hnovel.rules.RuleValue.Empty, "fixture").text()).jsonObject
                assertEquals("bob", submitted.getValue("user").jsonPrimitive.content)
                assertEquals("west", submitted.getValue("region").jsonPrimitive.content)
                assertEquals(0, fixture.documents.get())
            }
        }
    }

    @Test fun scriptsNeedTheExtensionProfileAndMalformedRowsHaveAFieldLocation() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + ("loginUi" to JsonPrimitive("@js:'[]'"))) }.use { source ->
                val failure = runCatching { source.loginForm() }.exceptionOrNull() as SourceContentException
                assertEquals("loginUi", failure.field)
            }
            for ((ui, field) in listOf(
                "[{\"name\":\"x\",\"unknown\":true}]" to "loginUi[0].unknown",
                "[{\"name\":\"x\",\"type\":\"select\",\"chars\":[\"a\"],\"default\":\"b\"}]" to "loginUi[0].default",
                "[{\"name\":\"x\",\"type\":\"arbitrary-view\"}]" to "loginUi[0].type"
            )) fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + ("loginUi" to JsonPrimitive(ui))) }.use { source ->
                val failure = runCatching { source.loginForm() }.exceptionOrNull() as SourceContentException
                assertEquals(field, failure.field)
            }
        }
    }

    @Test fun invalidChoiceAndFailedActionDoNotPublishAChangedForm() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + mapOf(
                "loginUrl" to JsonPrimitive("function login(){}"),
                "loginUi" to JsonPrimitive("""[{"name":"region","type":"select","chars":["east","west"]},
                    {"name":"fail","type":"button","action":"java.upLoginData({region:'west'});throw 'synthetic-secret';"},
                    {"name":"oversize","type":"button","action":"var values={};for(var i=0;i<33;i++)values['extra'+i]='x';java.upLoginData(values);"}]""")
            )) }.use { source ->
                val initial = source.loginForm()
                assertTrue(runCatching { source.login(mapOf("region" to "invalid")) }.isFailure)
                val failure = runCatching { source.login(initial.values, "fail") }.exceptionOrNull()!!
                assertFalse(failure.toString().contains("synthetic-secret"))
                assertEquals("east", source.loginForm().values["region"])
                assertTrue(runCatching { source.login(initial.values, "oversize") }.isFailure)
                assertEquals("1", source.evaluation().script("String(Object.keys(JSON.parse(source.getLoginInfo())).length)", hnovel.rules.RuleValue.Empty, "fixture").text())
            }
        }
    }
}
