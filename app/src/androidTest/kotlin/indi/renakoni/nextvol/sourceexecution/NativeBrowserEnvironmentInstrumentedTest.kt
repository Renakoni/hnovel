package indi.renakoni.nextvol.sourceexecution

import android.os.Bundle
import android.os.Build
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.sourcebrowser.BrowserTestHostActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** Owned loopback sites only: no real-site data, identity overrides or TLS exceptions. */
@RunWith(AndroidJUnit4::class)
class NativeBrowserEnvironmentInstrumentedTest {
    @Test fun crossSiteCookiesAndRealms(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "cookie-evidence-${UUID.randomUUID()}")
        val probe = listOf("realm-probe.js", "canvas-backend-probe.js").joinToString("\n") { name ->
            // Git checkout line endings must not change the fixture identity between Windows and CI.
            instrumentation.context.assets.open(name).bufferedReader().use { it.readText().replace("\r\n", "\n") }
        } + """
            async function environment() {
                var result=await collectBrowserRealm();
                result.webdriverPresent='webdriver' in navigator;
                result.canvasBackends=collectCanvasBackend();
                return result;
            }
        """.trimIndent()
        val scriptHash = MessageDigest.getInstance("SHA-256").digest(probe.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val defaultUa = WebSettings.getDefaultUserAgent(context)
        val provider = WebViewCompat.getCurrentWebViewPackage(context)
        val observations = mutableListOf<JsonObject>()
        val report = buildJsonObject {
            put("schema", 1); put("probeSha256", scriptHash)
            put("provider", provider?.packageName.orEmpty()); put("version", provider?.versionName.orEmpty())
            put("sdk", Build.VERSION.SDK_INT); put("os", Build.VERSION.RELEASE)
            put("model", Build.MODEL); put("hardware", Build.HARDWARE)
        }
        fun record(sample: JsonObject) {
            observations += sample
            instrumentation.sendStatus(0, Bundle().apply { putString("nativeEnvironment", sample.toString()) })
        }
        record(report)
        try { MockWebServer().use { server ->
            server.start()
            val siteA = "http://localhost:${server.port}"
            val siteB = "http://127.0.0.1:${server.port}"
            val names = "document.cookie.split(';').map(function(s){return s.trim().split('=')[0]}).filter(Boolean).sort()"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val sent = request.getHeader("Cookie").orEmpty().split(';').map { it.trim().substringBefore('=') }.filter(String::isNotBlank).sorted()
                    val encoded = buildJsonArray { sent.forEach { add(it) } }
                    val response = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Cache-Control", "no-store")
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/realm.js") return response.setHeader("Content-Type", "application/javascript").setBody(probe)
                    if (path == "/worker.js") return response.setHeader("Content-Type", "application/javascript")
                        .setBody("importScripts('/realm.js');environment().then(function(r){postMessage(r)})")
                    if (path == "/fetch") return response.setHeader("Access-Control-Allow-Origin", siteA)
                        .setHeader("Access-Control-Allow-Credentials", "true").setBody(encoded.toString())
                    if (path == "/set") {
                        response.addHeader("Set-Cookie", "strict=fixture; SameSite=Strict; Path=/")
                        response.addHeader("Set-Cookie", "lax=fixture; SameSite=Lax; Path=/")
                        response.addHeader("Set-Cookie", "none=fixture; SameSite=None; Secure; Path=/")
                        response.addHeader("Set-Cookie", "hidden=fixture; SameSite=None; Secure; HttpOnly; Path=/")
                        response.addHeader("Set-Cookie", "invalid=fixture; SameSite=None; Path=/")
                        response.addHeader("Set-Cookie", "pathOnly=fixture; SameSite=Lax; Path=/restricted")
                    }
                    val script = when (path) {
                        "/probe" -> """
                            var f=document.createElement('iframe');f.src=${JsonPrimitive("$siteB/frame")};document.body.appendChild(f);
                            var frame=new Promise(function(resolve){addEventListener('message',function receive(e){
                                if(e.origin===${JsonPrimitive(siteB)} && e.source===f.contentWindow){removeEventListener('message',receive);resolve(e.data)}
                            })});
                            var worker=new Promise(function(resolve,reject){var w=new Worker('/worker.js');
                                w.onmessage=function(e){resolve(e.data);w.terminate()};w.onerror=function(){w.terminate();reject('worker failed')}});
                            Promise.all([environment(),frame,fetch(${JsonPrimitive("$siteB/fetch")},{credentials:'include'}).then(function(r){return r.json()}),worker])
                                .then(function(r){window.answer=JSON.stringify({top:r[0],frame:r[1],fetchNames:r[2],worker:r[3],networkUa:${JsonPrimitive(request.getHeader("User-Agent"))}})})
                                .catch(function(){window.answer=JSON.stringify({error:'realm probe failed'})});
                        """.trimIndent()
                        "/frame" -> "environment().then(function(r){parent.postMessage({sentNames:$encoded,documentNames:$names,environment:r,networkUa:${JsonPrimitive(request.getHeader("User-Agent"))}},${JsonPrimitive(siteA)})})"
                        else -> "window.answer=JSON.stringify({sentNames:$encoded,documentNames:$names})"
                    }
                    return response.setBody("<html><head><link rel='icon' href='data:,'><script src='/realm.js'></script></head><body><script>$script</script></body></html>")
                }
            }
            var previousIdentity: JsonObject? = null
            for (foreground in listOf(false, true)) {
                val activity = if (foreground) ActivityScenario.launch(BrowserTestHostActivity::class.java) else null
                try { SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                    val session = broker.open(SourceScope("cookie-evidence", UUID.randomUUID().toString(), "legado"),
                        listOf(NetworkGrant(siteA, true), NetworkGrant(siteB, true)))
                    session.configureSource(siteB, true, browserRead = true)
                    suspend fun read(url: String): JsonObject {
                        val result = session.execute(BrokerRequest("evidence", url, timeoutMillis = 60000,
                            browser = BrowserOptions(script = "window.answer || null", interactive = foreground)))
                        assertTrue("Native fixture request failed", result is BrokerResult.Success)
                        return Json.parseToJsonElement((result as BrokerResult.Success).response.text()).jsonObject
                    }
                    try { repeat(2) { iteration ->
                        read("$siteB/set")
                        val top = read("$siteB/read")
                        val cross = read("$siteA/probe")
                        val restricted = read("$siteB/restricted/read")
                        val sample = buildJsonObject {
                            put("foreground", foreground); put("iteration", iteration)
                            put("top", top); put("cross", cross); put("restricted", restricted)
                        }
                        record(sample) // Preserve evidence even when the next assertion fails.
                        assertNames(top, "sentNames", "hidden", "lax", "none", "strict")
                        assertNames(top, "documentNames", "lax", "none", "strict")
                        assertNames(restricted, "sentNames", "hidden", "lax", "none", "pathOnly", "strict")
                        assertNames(restricted, "documentNames", "lax", "none", "pathOnly", "strict")
                        val frame = cross.getValue("frame").jsonObject
                        assertNames(cross, "fetchNames", "hidden", "none")
                        assertNames(frame, "sentNames", "hidden", "none")
                        assertNames(frame, "documentNames", "none")
                        val environment = cross.getValue("top").jsonObject
                        val frameEnvironment = frame.getValue("environment").jsonObject
                        val worker = cross.getValue("worker").jsonObject
                        val currentIdentity = identity(environment)
                        assertEquals(defaultUa, environment.getValue("userAgent").jsonPrimitive.content)
                        assertEquals(environment["userAgent"], cross["networkUa"])
                        assertEquals(environment["userAgent"], frame["networkUa"])
                        assertEquals(currentIdentity, identity(frameEnvironment)); assertEquals(currentIdentity, identity(worker))
                        previousIdentity?.let { assertEquals(it, currentIdentity) }; previousIdentity = currentIdentity
                        for (page in listOf(environment, frameEnvironment)) {
                            assertFalse(page.getValue("webdriver").jsonPrimitive.boolean)
                            assertTrue(page.getValue("webdriverPresent").jsonPrimitive.boolean)
                            assertFalse(page.getValue("privilegedBridge").jsonPrimitive.boolean)
                            assertFalse(page.getValue("cookieOwnProperty").jsonPrimitive.boolean)
                        }
                        // W3C exposes NavigatorAutomationInformation on Navigator, not WorkerNavigator.
                        assertFalse(worker.getValue("webdriverPresent").jsonPrimitive.boolean)
                        for (realm in listOf(environment, frameEnvironment, worker)) {
                            assertTrue(realm.getValue("fetchNative").jsonPrimitive.boolean)
                            assertTrue(realm.getValue("secureContext").jsonPrimitive.boolean)
                            assertTrue(realm.getValue("canvas").jsonObject.getValue("repeatEqual").jsonPrimitive.boolean)
                        }
                    } } finally { session.clearAccount() }
                } } finally { activity?.close() }
            }
        } } finally {
            // Instrumentation results are CI artifacts. Only our fixture observations are written.
            val output = File(context.getExternalFilesDir(null), "native-environment.json")
            output.writeText(buildJsonArray { observations.forEach { add(it) } }.toString())
            root.deleteRecursively()
        }
    }

    private fun assertNames(result: JsonObject, key: String, vararg names: String) =
        assertEquals(key, names.toList(), result.getValue(key).jsonArray.map { it.jsonPrimitive.content })

    private fun identity(result: JsonObject) = JsonObject(listOf("userAgent", "platform", "languages",
        "hardwareConcurrency", "deviceMemory", "locale").associateWith(result::getValue))
}
