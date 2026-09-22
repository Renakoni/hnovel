package indi.renakoni.nextvol.sourceexecution

import android.content.ContextWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.sourcebrowser.*
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceCertificateInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun server() = MockWebServer().apply {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .validityInterval(System.currentTimeMillis() - 172800000, System.currentTimeMillis() - 86400000).build()
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<html><head><link rel='icon' href='data:,'></head><body><li><a href='/book'>Certificate fixture</a></li><article>Readable chapter</article></body></html>")
        }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private fun url(server: MockWebServer) = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    private fun step(value: String) = instrumentation.sendStatus(0, android.os.Bundle().apply {
        putString("certificateStep", value)
    })
    private suspend fun click(text: String) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        step("click: $text")
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && node.text?.toString()?.equals(text, ignoreCase = true) == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        try {
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
        } catch (failure: TimeoutCancellationException) {
            val labels = mutableListOf<String>()
            fun record(node: AccessibilityNodeInfo?) {
                node ?: return
                node.text?.let { labels += "$it (visible=${node.isVisibleToUser}, clickable=${node.isClickable})" }
                for (index in 0 until node.childCount) record(node.getChild(index))
            }
            automation.windows.mapNotNull { it.root }.filter { it.packageName == context.packageName }.forEach(::record)
            step("click timed out: $text; fixture labels: $labels")
            throw failure
        }
    }

    @Test fun confirmationSurvivesRecreationAndRetriesOnlyAfterTheUserAccepts(): Unit = runBlocking {
        server().use { server ->
            val root = File(context.cacheDir, "certificate-ui-${UUID.randomUUID()}").apply { mkdirs() }
            val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
            val authority = ExecutionAuthority()
            val registry = WebSourceRegistry(authority)
            val coordinator = SourceVerificationCoordinator(registry)
            val executor = AndroidIsolatedExecutor(context, authority)
            val sources = ImportedRuleSources(host, registry, authority, SourceSessionManager(authority),
                indi.renakoni.nextvol.di.WebDataSourceModule.provideRuleTaskRunner(executor), verification = coordinator)
            VerificationTestHostActivity.coordinator = coordinator
            try {
                val raw = buildJsonObject {
                    put("bookSourceUrl", url(server)); put("bookSourceName", "Certificate fixture"); put("bookSourceType", 0)
                    put("searchUrl", "/search?key={{key}}")
                    put("ruleSearch", buildJsonObject { put("bookList", "li"); put("name", "a@text"); put("bookUrl", "a@href") })
                }
                val saved = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(url(server), true)))
                suspend fun search(): SearchResult = withContext(ForegroundSourceRequest()) {
                    val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                    runtime.search.search(runtime.search.searchTypes.first(), "fixture").first()
                }
                ActivityScenario.launch(VerificationTestHostActivity::class.java).use { activity ->
                    step("request first confirmation")
                    val request = async(Dispatchers.Default) { search() }
                    val prompt = withTimeout(20000) { coordinator.prompts.first { it.any { it.confirmingCertificate } }.single() }
                    assertNotNull(prompt.certificate)
                    assertEquals(0, server.requestCount)
                    step("recreate with confirmation pending")
                    activity.recreate()
                    step("accept confirmation")
                    click(context.getString(R.string.source_certificate_continue))
                    step("await confirmed request")
                    assertTrue(request.await() is SearchResult.MultipleBook)
                    assertEquals(1, sources.storedSettings(id).certificates.size)
                    step("revoke exception")
                    sources.revokeCertificate(id, prompt.certificate!!.origin)
                    assertTrue(sources.storedSettings(id).certificates.isEmpty())
                    step("request confirmation after revocation")
                    val refused = async(Dispatchers.Default) { search() }
                    withTimeout(20000) { coordinator.prompts.first { it.any { it.confirmingCertificate } } }
                    val requestsBeforeCancel = server.requestCount
                    step("cancel confirmation")
                    click(context.getString(android.R.string.cancel))
                    step("await cancelled request")
                    assertTrue(refused.await() is SearchResult.Error)
                    assertEquals(requestsBeforeCancel, server.requestCount)
                    assertTrue(sources.storedSettings(id).certificates.isEmpty())
                }
            } finally {
                sources.stop(); executor.close(); VerificationTestHostActivity.coordinator = null
                root.deleteRecursively()
            }
        }
    }

    @Test fun httpAndNativeBrowserShareOnlyTheConfirmedCertificateAndBothRespectRevocation(): Unit = runBlocking {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 28)
        val root = File(context.cacheDir, "certificate-native-${UUID.randomUUID()}")
        server().use { server -> SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
            val owner = SourceScope("certificate-tests", UUID.randomUUID().toString(), "legado")
            fun session() = broker.open(owner, listOf(NetworkGrant(url(server), true))).apply {
                configureSource(url(server), true, browserRead = true, defaultUserAgent = DESKTOP_USER_AGENT)
            }
            val account = session()
            try {
                val request = BrokerRequest("certificate", url(server), timeoutMillis = 60000)
                val first = account.executeHttp(request, RequestCommitGuard { it() }) as BrokerResult.Failure
                assertEquals(FailureCode.Certificate, first.code)
                assertNotNull(first.certificate)
                assertEquals(0, server.requestCount)
                account.approveCertificate(first.certificate!!)
                assertTrue(account.executeHttp(request, RequestCommitGuard { it() }) is BrokerResult.Success)
                val rendered = account.execute(request.copy(browser = BrowserOptions(script = "document.querySelector('article').textContent")))
                assertTrue(rendered.toString(), rendered is BrokerResult.Success)
                assertEquals("Readable chapter", (rendered as BrokerResult.Success).response.text())
                account.revokeCertificate(first.certificate!!.origin)
                val fresh = session()
                try {
                    val blocked = fresh.execute(request) as BrokerResult.Failure
                    assertEquals(FailureCode.Certificate, blocked.code)
                    assertNotNull(blocked.certificate)
                    assertEquals(first.certificate!!.fingerprint, blocked.certificate!!.fingerprint)
                } finally { fresh.clearAccount() }
            } finally { account.clearAccount() }
        } }
        root.deleteRecursively()
    }
}
