package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.content.ContextWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.R
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
        if (node.isVisibleToUser && (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true || node.className == text)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }

    private suspend fun click(text: String) {
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("verificationStep", "click: $text") })
        withTimeout(20000) {
        while (true) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeout
            delay(100)
        }
        }
    }

    @Test fun promptSurvivesRecreationAndVerificationResumesTheOriginalSearch(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            val ordinary = fixture.server.dispatcher
            val challenged = java.util.concurrent.atomic.AtomicInteger()
            val accepted = CompletableDeferred<Unit>()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    return when {
                        // The owned server approves the second visit, after the user
                        // opens verification. No external CAPTCHA is automated.
                        path == "/antibot" -> MockResponse().setHeader("Content-Type", "text/html").setBody(
                            "<html><title>Site verification</title>" +
                                if (challenged.get() >= 2) "<script>location.replace('/accepted')</script></html>" else "</html>")
                        path == "/accepted" -> MockResponse().setHeader("Content-Type", "text/html")
                            .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                            .setBody("<html><title>Verified fixture</title><p>Return to reader</p></html>")
                            .also { accepted.complete(Unit) }
                        path == "/search" && !request.getHeader("Cookie").orEmpty().contains("verified=fixture") -> {
                            challenged.incrementAndGet()
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
                    val prompt = withTimeout(45000) { coordinator.prompts.first { it.isNotEmpty() }.single() }
                    assertTrue(prompt.foreground)
                    assertFalse(request.isCompleted)
                    activity.recreate()
                    assertEquals(prompt.id, coordinator.prompts.value.single().id)
                    click(context.getString(R.string.source_verification_open))
                    withTimeout(20000) { accepted.await() }
                    click(context.getString(R.string.source_browser_done))
                    assertTrue(withTimeout(45000) { request.await() } is SearchResult.MultipleBook)
                    assertTrue(challenged.get() >= 2)
                    assertTrue(coordinator.prompts.value.isEmpty())
                }
            } finally {
                runCatching { sources.loginTarget(sources.installedSources().single().let { ImportedRuleSources.id(it.definition) }).session.clearAccount() }
                sources.stop(); executor.close(); VerificationTestHostActivity.coordinator = null
                root.deleteRecursively()
            }
        }
    }
}
