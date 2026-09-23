package indi.renakoni.nextvol.sourceexecution

import android.content.ContextWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.getOrElse
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.sourcebrowser.*
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Production importer, isolated worker, verification UI and native browser on a controlled website. */
@RunWith(AndroidJUnit4::class)
class SourceVerificationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        node ?: return null
        // Platform Buttons expose transformed (all-caps in English) text to accessibility.
        if (node.isVisibleToUser && (node.text?.contains(text, ignoreCase = true) == true ||
                node.contentDescription?.contains(text, ignoreCase = true) == true || node.className == text)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }

    private suspend fun awaitVisible(text: String) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("verificationStep", "visible: $text") })
        try {
            withTimeout(20000) {
                // Emulator overlays can own rootInActiveWindow while the browser remains visible.
                while (automation.windows.none { window ->
                    window.root?.let { root -> root.packageName == context.packageName && find(root, text) != null } == true
                }) delay(100)
            }
        } catch (failure: TimeoutCancellationException) {
            val nodes = mutableMapOf<String, Int>()
            fun record(node: AccessibilityNodeInfo?) {
                node ?: return
                val key = "${node.packageName}/${node.className}/visible=${node.isVisibleToUser}"
                nodes[key] = (nodes[key] ?: 0) + 1
                for (index in 0 until node.childCount) record(node.getChild(index))
            }
            automation.windows.forEach { record(it.root) }
            record(automation.rootInActiveWindow)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("verificationNodes", nodes.toString()) })
            throw failure
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("verificationStep", "found: $text") })
    }

    @Test fun verificationOpensAutomaticallyAfterHostRecreationAndResumesTheOriginalSearch() = verifySearch(true)

    @Test fun httpVerificationOpensNativeBrowserAndResumesTheOriginalSearch() = verifySearch(false)

    @Test fun wafPostVerificationWaitsForSuccessAndResumesAllReadingStages() = verifySearch(false, waf = true)

    @Test fun nativeWafVerificationWaitsForSuccessAndResumesAllReadingStages() = verifySearch(true, waf = true)

    private fun verifySearch(browserRead: Boolean, waf: Boolean = false): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            val ordinary = fixture.server.dispatcher
            val challenged = java.util.concurrent.atomic.AtomicInteger()
            val accepted = CompletableDeferred<Unit>()
            val allowVerification = java.util.concurrent.atomic.AtomicBoolean()
            val started = CompletableDeferred<Unit>()
            val wafLoaded = CompletableDeferred<Unit>()
            val postBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
            val verifiedPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
            val releaseChallenge = java.util.concurrent.CountDownLatch(1)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path == "/search" && request.method == "POST") postBodies += request.body.clone().readUtf8()
                    return when {
                        path == "/@wafjs" -> MockResponse().setHeader("Content-Type", "application/javascript")
                            .setBody("setInterval(function(){fetch('/fixture-status').then(r=>r.text()).then(v=>{if(v==='ok')location.replace('/captcha?_waform')})},200)")
                            .also { wafLoaded.complete(Unit) }
                        path == "/captcha" -> MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html")
                            .setBody("<html><head><link rel='icon' href='data:,'></head><body>" +
                                "<form action='/accepted?_waform' method='post'><input name='__input' value='fixture'>" +
                                "<button>Accept fixture</button></form></body></html>")
                        // The owned fixture waits until the test observes a visible browser.
                        // No external CAPTCHA is automated.
                        path == "/antibot" -> MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store").setBody(
                            "<html><title>Site verification</title><p>Verification fixture</p>" +
                                "<script>setInterval(function(){fetch('/fixture-status').then(r=>r.text()).then(v=>{if(v==='ok')location.replace('/accepted')})},200)</script></html>")
                        path == "/fixture-status" -> MockResponse().setBody(if (allowVerification.get()) "ok" else "wait")
                        path == "/accepted" && waf && (request.method != "POST" || request.body.clone().readUtf8() != "__input=fixture") ->
                            MockResponse().setResponseCode(403)
                        path == "/accepted" -> MockResponse().setHeader("Content-Type", "text/html")
                            .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                            .setBody("<html><title>Verified fixture</title><p>Return to reader</p></html>")
                            .also { android.util.Log.i("VerificationFixture", "Owned website accepted verification"); accepted.complete(Unit) }
                        path == "/search" && !request.getHeader("Cookie").orEmpty().contains("verified=fixture") -> {
                            challenged.incrementAndGet()
                            started.complete(Unit)
                            check(releaseChallenge.await(20, java.util.concurrent.TimeUnit.SECONDS))
                            if (!waf) MockResponse().setResponseCode(302).setHeader("Cache-Control", "no-store").setHeader("Location", "/antibot")
                            else if (!request.getHeader("Cookie").orEmpty().contains("_wa_=fixture"))
                                MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html")
                                    .addHeader("Set-Cookie", "_wa_=fixture; HttpOnly; Path=/; Max-Age=30")
                                    .setBody("<meta http-equiv=refresh content=0>")
                            else MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html")
                                .setHeader("Cache-Control", "no-store").setBody(
                                    "<html><head><title>loading fixture</title><link rel='icon' href='data:,'></head>" +
                                        "<body>WAF fixture<script src='/@wafjs?fixture'></script></body></html>")
                        }
                        else -> ordinary.dispatch(request).also {
                            if (request.getHeader("Cookie").orEmpty().contains("verified=fixture")) verifiedPaths += path
                        }
                    }
                }
            }
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val coordinator = SourceVerificationCoordinator(registry)
            val executor = AndroidIsolatedExecutor(context, fixture.authority)
            val runner = indi.renakoni.nextvol.di.WebDataSourceModule.provideRuleTaskRunner(executor)
            val root = File(context.cacheDir, "verification-${UUID.randomUUID()}").apply { mkdirs() }
            val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, runner,
                browser = AndroidSourceBrowser(context), verification = coordinator)
            VerificationTestHostActivity.coordinator = coordinator
            try {
                val raw = JsonObject(fixture.raw() + mapOf("browserRead" to JsonPrimitive(browserRead)) +
                    if (waf && !browserRead) mapOf("searchUrl" to JsonPrimitive("/search,{\"method\":\"POST\",\"body\":\"keyword={{key}}\"}")) else emptyMap())
                val saved = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                ActivityScenario.launch(VerificationTestHostActivity::class.java).use { activity ->
                    val request = async(Dispatchers.Default + ForegroundSourceRequest()) {
                        runtime.search.search(runtime.search.searchTypes.first(), "fixture").first()
                    }
                    withTimeout(45000) { started.await() }
                    assertFalse(request.isCompleted)
                    activity.recreate()
                    releaseChallenge.countDown()
                    // Observe the website window itself, independently of locale or toolbar style.
                    awaitVisible("android.webkit.WebView")
                    assertTrue(coordinator.prompts.value.single().opening)
                    if (waf) {
                        withTimeout(20000) { wafLoaded.await() }
                        // Keep the owned challenge present past the browser's extraction delay.
                        // Returning its 401 document early must not finish the original search.
                        delay(2000)
                        assertFalse(request.isCompleted)
                    }
                    allowVerification.set(true)
                    if (waf) {
                        awaitVisible("Accept fixture")
                        delay(1500)
                        assertFalse(request.isCompleted)
                        withTimeout(10000) {
                            while (true) {
                                var button = instrumentation.uiAutomation.windows.firstNotNullOfOrNull { find(it.root, "Accept fixture") }
                                while (button != null && !button.isClickable) button = button.parent
                                if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                                delay(100)
                            }
                        }
                    }
                    withTimeout(20000) { accepted.await() }
                    assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Verify and continue"))
                    val found = withTimeout(45000) { request.await() }
                    if (found is SearchResult.Error) throw AssertionError("Search did not resume", found.error)
                    assertTrue(found is SearchResult.MultipleBook)
                    assertTrue(challenged.get() >= 2)
                    assertTrue(coordinator.prompts.value.isEmpty())
                    if (waf) {
                        val book = (found as SearchResult.MultipleBook).bookId
                        assertEquals("Same title", runtime.getBookInformation(book).getOrElse { throw AssertionError(it) }.title)
                        val volumes = runtime.getBookVolumes(book).getOrElse { throw AssertionError(it) }
                        assertEquals(2, volumes.volumes.sumOf { it.chapters.size })
                        val chapter = runtime.getChapterContent(fixture.server.url("/c/2").toString(), book)
                            .getOrElse { throw AssertionError(it) }
                        assertTrue(chapter.content.toString().contains("second chapter"))
                        assertTrue(verifiedPaths.containsAll(listOf("/search", "/book/one", "/toc/1", "/toc/2", "/c/2")))
                        if (!browserRead) assertEquals(listOf("keyword=fixture", "keyword=fixture", "keyword=fixture"), postBodies)
                    }
                }
            } finally {
                releaseChallenge.countDown()
                runCatching { sources.loginTarget(sources.installedSources().single().let { ImportedRuleSources.id(it.definition) }).session.clearAccount() }
                sources.stop(); executor.close(); VerificationTestHostActivity.coordinator = null
                root.deleteRecursively()
            }
        }
    }
}
