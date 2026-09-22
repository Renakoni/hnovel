package indi.renakoni.nextvol.sourceexecution

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.content.*
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.sourcebrowser.BrowserTestHostActivity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpDirectoryVerificationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private suspend fun click(text: String) {
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("directoryVerificationStep", "click: $text") })
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && (node.text?.toString()?.equals(text, true) == true ||
                    node.contentDescription?.toString()?.equals(text, true) == true)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        withTimeout(20000) {
            while (true) {
                var node = automation.windows.firstNotNullOfOrNull { window ->
                    window.root?.takeIf { it.packageName == context.packageName }?.let(::find)
                }
                while (node != null && !node.isClickable) node = node.parent
                if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeout
                delay(100)
            }
        }
    }

    @Test fun cancelledCaptchaNeverSavesAPartialTocAndNativeVerificationResumesHttpPages(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use {
            RuleSourceFixture(AndroidSourceBrowser(context)).use { fixture ->
                val original = fixture.server.dispatcher
                val httpPages = java.util.concurrent.atomic.AtomicInteger()
                fixture.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.requestUrl!!.encodedPath == "/toc/2" && !request.getHeader("Cookie").orEmpty().contains("verified=fixture") ->
                            MockResponse().setResponseCode(302).setHeader("Location", "/WAF/VERIFY/CAPTCHA?next=/toc/2")
                        request.requestUrl!!.encodedPath == "/WAF/VERIFY/CAPTCHA" ->
                            MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store").setBody("""
                                <html><head><title>Verify Yourself</title><link rel='icon' href='data:,'></head>
                                <body><form id='ui-form' action='/accepted'><button>Unlock fixture</button></form></body></html>
                            """.trimIndent())
                        request.requestUrl!!.encodedPath == "/accepted" && !request.getHeader("Cookie").orEmpty().contains("bootstrap=fixture") ->
                            MockResponse().setResponseCode(403)
                        request.requestUrl!!.encodedPath == "/accepted" ->
                            MockResponse().setResponseCode(302).setHeader("Location", "/toc/2")
                                .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                        else -> original.dispatch(request).also {
                            if (request.requestUrl!!.encodedPath == "/toc/2" && request.getHeader("Sec-Fetch-Mode") == null)
                                httpPages.incrementAndGet()
                        }
                    }
                }
                fixture.source { raw -> JsonObject(raw + ("enabledCookieJar" to JsonPrimitive(false))) }.use { source ->
                    val session = fixture.broker.open(SourceScope("rules", source.definition.sourceId, source.definition.profile),
                        listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                    try {
                        session.setCookie(fixture.server.url("/").toString(), "bootstrap=fixture")
                        val book = source.search("fixture").single()
                        suspend fun blocked(): SourceContentException = try {
                            source.directory(book.id)
                            error("Partial directory was returned")
                        } catch (failure: SourceContentException) {
                            assertEquals(ContentError.BrowserRequired, failure.code)
                            assertEquals(BrowserChallengeKind.SiteVerification, failure.verification!!.kind)
                            failure
                        }
                        val first = blocked()
                        val cancelled = async(Dispatchers.Default) { runCatching { first.verification!!.complete() } }
                        click(context.getString(android.R.string.cancel))
                        assertTrue(withTimeout(30000) { cancelled.await() }.isFailure)
                        val second = blocked()
                        assertEquals(0, httpPages.get())
                        val accepted = async(Dispatchers.Default) { second.verification!!.complete() }
                        click("Unlock fixture")
                        withTimeout(30000) { accepted.await() }
                        val chapters = source.directory(book.id)
                        assertEquals(listOf("Volume one", "One", "Two"), chapters.map { it.title })
                        assertTrue(httpPages.get() > 0)
                        assertTrue(source.content(book.id, chapters.last().id).parts.any { it.text == "second chapter" })
                        assertTrue(session.cookie(fixture.server.url("/").toString()).contains("verified=fixture"))
                    } finally { session.clearAccount() }
                }
            }
        }
    }
}
