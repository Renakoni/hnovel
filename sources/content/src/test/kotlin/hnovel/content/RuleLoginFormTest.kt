package hnovel.content

import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.BrowserExecutor
import hnovel.network.BrokerResult
import hnovel.network.FailureCode
import hnovel.network.OriginDenial
import hnovel.network.RequestStage
import hnovel.network.ResourceKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleLoginFormTest {
    @Test fun nestedBusinessDataOnlyFillsDeclaredStringInputsAndSurvivesFormActions() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + mapOf(
                "loginUrl" to JsonPrimitive("function login(){}"),
                "loginUi" to JsonPrimitive("""[{"name":"user"},{"name":"id","default":"fallback"},
                    {"name":"update","type":"button","action":"java.upLoginData({user:'bob'});"}]""")
            )) }.use { source ->
                val novel = """ {"user":"alice","id":7,"liked":true,"tags":["one"],"pollData":{"votes":[1,2]},"note":"business"} """
                suspend fun script(code: String) = source.evaluation().script(code, hnovel.rules.RuleValue.Empty, "fixture").text()
                assertEquals("true", script("source.putLoginInfo(${JsonPrimitive(novel)})"))
                assertEquals(novel, script("source.getLoginInfo()"))
                assertEquals("alice", script("source.getLoginInfoMap().get('user')"))
                val form = source.loginForm()
                assertEquals(mapOf("user" to "alice", "id" to "fallback"), form.values)
                assertTrue(runCatching { source.login(mapOf("note" to "injected")) }.isFailure)
                assertEquals(novel, script("source.getLoginInfo()"))
                source.login(form.values, "update")
                val saved = Json.parseToJsonElement(script("source.getLoginInfo()")).jsonObject
                assertEquals("bob", saved.getValue("user").jsonPrimitive.content)
                assertEquals(Json.parseToJsonElement(novel).jsonObject["pollData"], saved["pollData"])
                assertEquals(JsonPrimitive("business"), saved["note"])
                assertEquals(JsonPrimitive(true), saved["liked"])
                assertEquals("bob", source.loginForm().values["user"])
            }
        }
    }

    @Test fun opaqueOrLargeBusinessFieldsDoNotPreventOpeningAnOrdinaryForm() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "loginUrl" to JsonPrimitive("function login(){}"),
                "loginUi" to JsonPrimitive("""[{"name":"user"}]""")
            )) }.use { source ->
                for (text in listOf("opaque", "[1,true]", JsonObject(mapOf("user" to JsonPrimitive("x".repeat(5000)))).toString())) {
                    source.evaluation().script("source.putLoginInfo(${JsonPrimitive(text)})", hnovel.rules.RuleValue.Empty, "fixture")
                    assertEquals(mapOf("user" to ""), source.loginForm().values)
                }
            }
        }
    }

    @Test fun sameNamedButtonsDispatchTheirOwnActionAndRefreshRejectsOldSubmissions() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + mapOf(
                "loginUrl" to JsonPrimitive("function login(){source.put('chosen','login');}"),
                "loginUi" to JsonPrimitive("""@js:
                    var rows=[{name:'user',default:'alice'},
                      {name:'same',type:'button',action:"source.put('chosen','one');"},
                      {name:'same',type:'button',action:"source.put('chosen','two');"},
                      {name:'expand',type:'button',action:"source.put('expanded',source.get('expanded')==='yes'?'no':'yes');java.reLoginView();"}];
                    if(source.get('expanded')==='yes') {
                      rows.reverse(); rows[1].name='new label';
                      for(var i=0;i<57;i++)rows.push({name:'setting'+i});
                    }
                    JSON.stringify(rows);
                """.trimIndent())
            )) }.use { source ->
                suspend fun chosen() = source.evaluation().script("source.get('chosen')", hnovel.rules.RuleValue.Empty, "fixture").text()
                val original = source.loginForm()
                val buttons = original.fields.filter { it.name == "same" }
                source.login(original.values, buttons[0].id, original.id)
                assertEquals("one", chosen())
                source.login(original.values, buttons[1].id, original.id)
                assertEquals("two", chosen())
                source.login(original.values, original.fields.single { it.name == "expand" }.id, original.id)
                val stale = runCatching { source.login(mapOf("user" to "stale"), buttons[0].id, original.id) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.Unavailable, stale.code)
                val expanded = source.loginForm()
                assertEquals(61, expanded.fields.size)
                assertEquals("alice", expanded.values["user"])
                assertTrue(expanded.fields.any { it.id == buttons[1].id && it.name == "new label" })
                assertTrue(runCatching { source.login(original.values, null, original.id) }.isFailure)
                source.login(expanded.values, buttons[0].id, expanded.id)
                assertEquals("one", chosen())
                source.login(expanded.values, expanded.fields.single { it.name == "expand" }.id, expanded.id)
                assertEquals(4, source.loginForm().fields.size)
                assertTrue(runCatching { source.login(expanded.values, buttons[1].id, expanded.id) }.isFailure)
                assertEquals("one", chosen())
                assertTrue(runCatching { source.login(original.values, "same") }.isFailure)
                source.login(original.values, "expand")
                assertEquals(61, source.loginForm().fields.size)
            }
        }
    }

    @Test fun directAndRedirectedBrowserLoginKeepTheDeniedOriginWithoutItsQuery() = runBlocking {
        val deniedUrl = "https://login.invalid/verify?token=synthetic-secret"
        var navigations = 0
        val browser = BrowserExecutor { session, request, options, guard, _ ->
            assertTrue(options.interactive)
            navigations++
            session.execute(request.copy(browser = null), guard)
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", deniedUrl)
            }
            for (loginUrl in listOf(deniedUrl, fixture.server.url("/login").toString())) {
                fixture.source { raw -> JsonObject(raw + ("loginUrl" to JsonPrimitive(loginUrl))) }.use { source ->
                    val failure = runCatching { source.login(emptyMap()) }.exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.PermissionDenied, failure.code)
                    assertEquals("loginUrl", failure.field)
                    assertEquals(OriginDenial("https://login.invalid:443", ResourceKind.Document), failure.denial)
                    assertFalse(failure.toString().contains("synthetic-secret"))
                }
            }
            assertEquals(1, navigations)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test fun directBrowserLoginPreservesBrokerFailures() = runBlocking {
        var code = FailureCode.Network
        val browser = BrowserExecutor { _, _, options, _, _ ->
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
                    FailureCode.Timeout to ContentError.Network,
                    FailureCode.ResponseTooLarge to ContentError.Limit,
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
        val browser = BrowserExecutor { session, request, options, guard, _ ->
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
                    val failure = runCatching { source.login(form.values, form.fields.single { it.name == "verify" }.id, form.id) }.exceptionOrNull() as SourceContentException
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
                source.login(form.values, form.fields.single { it.name == "change" }.id, form.id)
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
                    {"name":"oversize","type":"button","action":"var values={};for(var i=0;i<129;i++)values['extra'+i]='x';java.upLoginData(values);"}]""")
            )) }.use { source ->
                val initial = source.loginForm()
                assertTrue(runCatching { source.login(mapOf("region" to "invalid")) }.isFailure)
                val failure = runCatching { source.login(initial.values, initial.fields.single { it.name == "fail" }.id, initial.id) }.exceptionOrNull()!!
                assertFalse(failure.toString().contains("synthetic-secret"))
                assertEquals("east", source.loginForm().values["region"])
                assertTrue(runCatching { source.login(initial.values, initial.fields.single { it.name == "oversize" }.id, initial.id) }.isFailure)
                assertEquals("1", source.evaluation().script("String(Object.keys(JSON.parse(source.getLoginInfo())).length)", hnovel.rules.RuleValue.Empty, "fixture").text())
            }
        }
    }
}
