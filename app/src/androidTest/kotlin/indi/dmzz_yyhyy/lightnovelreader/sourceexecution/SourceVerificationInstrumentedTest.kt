package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.content.ContextWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.*
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.*
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
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("verificationStep", "visible: $text") })
        withTimeout(20000) {
            while (find(instrumentation.uiAutomation.rootInActiveWindow, text) == null) {
                delay(100)
            }
        }
    }

    @Test fun verificationOpensAutomaticallyAfterHostRecreationAndResumesTheOriginalSearch(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            val ordinary = fixture.server.dispatcher
            val challenged = java.util.concurrent.atomic.AtomicInteger()
            val accepted = CompletableDeferred<Unit>()
            val allowVerification = java.util.concurrent.atomic.AtomicBoolean()
            val started = CompletableDeferred<Unit>()
            val releaseChallenge = java.util.concurrent.CountDownLatch(1)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    return when {
                        // The owned fixture waits until the test observes a visible browser.
                        // No external CAPTCHA is automated.
                        path == "/antibot" -> MockResponse().setHeader("Content-Type", "text/html").setBody(
                            "<html><title>Site verification</title>" +
                                if (challenged.get() >= 2) "<script>setInterval(function(){fetch('/fixture-status').then(r=>r.text()).then(v=>{if(v==='ok')location.replace('/accepted')})},200)</script></html>" else "</html>")
                        path == "/fixture-status" -> MockResponse().setBody(if (allowVerification.get()) "ok" else "wait")
                        path == "/accepted" -> MockResponse().setHeader("Content-Type", "text/html")
                            .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                            .setBody("<html><title>Verified fixture</title><p>Return to reader</p></html>")
                            .also { android.util.Log.i("VerificationFixture", "Owned website accepted verification"); accepted.complete(Unit) }
                        path == "/search" && !request.getHeader("Cookie").orEmpty().contains("verified=fixture") -> {
                            challenged.incrementAndGet()
                            started.complete(Unit)
                            check(releaseChallenge.await(20, java.util.concurrent.TimeUnit.SECONDS))
                            MockResponse().setResponseCode(302).setHeader("Location", "/antibot")
                        }
                        else -> ordinary.dispatch(request)
                    }
                }
            }
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val coordinator = SourceVerificationCoordinator(registry)
            val executor = AndroidIsolatedExecutor(context, fixture.authority)
            val runner = indi.dmzz_yyhyy.lightnovelreader.di.WebDataSourceModule.provideRuleTaskRunner(executor)
            val root = File(context.cacheDir, "verification-${UUID.randomUUID()}").apply { mkdirs() }
            val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, runner,
                browser = AndroidSourceBrowser(context), verification = coordinator)
            VerificationTestHostActivity.coordinator = coordinator
            try {
                val raw = JsonObject(fixture.raw() + ("browserRead" to JsonPrimitive(true)))
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
                    awaitVisible("android.webkit.WebView")
                    assertTrue(coordinator.prompts.value.single().opening)
                    allowVerification.set(true)
                    withTimeout(20000) { accepted.await() }
                    assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Verify and continue"))
                    assertTrue(withTimeout(45000) { request.await() } is SearchResult.MultipleBook)
                    assertTrue(challenged.get() >= 2)
                    assertTrue(coordinator.prompts.value.isEmpty())
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
