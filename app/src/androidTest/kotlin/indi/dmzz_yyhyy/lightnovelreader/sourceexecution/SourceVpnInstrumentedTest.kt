package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.AndroidSourceNetworks
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceNetworkSettings
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.AndroidSourceBrowser
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Explicitly opted-in Clash fixtures. Ordinary connected tests do not contact public sites. */
@RunWith(AndroidJUnit4::class)
class SourceVpnInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var marker = "hnovel-route-fixture"

    @Test fun bypassPreferenceSurvivesClashStopAndRestart(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in to controlling the prepared Clash fixture", args.getString("sourceRouteCycle") == "true")
        val url = checkNotNull(args.getString("sourceRouteUrl"))
        val root = File(context.cacheDir, "vpn-cycle-${System.nanoTime()}")
        val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val id = Identifier("route-fixture", "persistent-bypass")
        try {
            AndroidSourceNetworks(context).use { networks ->
                val settings = SourceNetworkSettings(host, networks)
                settings.setBypassVpn(id, true)
                SourceBroker(root.toPath(), route = settings.forSource(id)).use { broker ->
                    val session = broker.open(SourceScope(id.namespace, id.id, "test"),
                        listOf(NetworkGrant(checkNotNull(sourceOrigin(url)), allowPrivateAddresses = true)))
                    val request = BrokerRequest("cycle", url, timeoutMillis = 15000, cache = CacheMode.Disabled)
                    for (vpn in listOf(false, true, false, true, false)) {
                        clash(vpn)
                        assertOutcome("persistent bypass, Android VPN=$vpn", "success", session.execute(request))
                        assertEquals(SourceNetworkMode.BypassVpn, SourceNetworkSettings(host, networks).mode(id))
                        assertEquals(0L, session.scope.accountGeneration)
                    }
                }
            }
        } finally { clash(false); root.deleteRecursively() }
    }

    /** Public-page transport observation only; a nested host VPN prevents physical-egress claims. */
    @Test fun publicSiteRouteProbe(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("sourceProbeUrl")
        assumeTrue("Supply an explicit public-page URL", url != null)
        val root = File(context.cacheDir, "route-probe-${System.nanoTime()}")
        try {
            AndroidSourceNetworks(context).use { networks ->
                for (mode in SourceNetworkMode.entries) {
                    SourceBroker(root.toPath(), route = SourceRouteProvider { networks.route(mode) }).use { broker ->
                        val session = broker.open(SourceScope("route-probe", mode.name, "test"),
                            listOf(NetworkGrant(checkNotNull(sourceOrigin(url!!)))))
                        val start = SystemClock.elapsedRealtime()
                        val result = session.execute(BrokerRequest("public-probe", url, timeoutMillis = 20000,
                            followRedirects = false, cache = CacheMode.Disabled))
                        val summary = when (result) {
                            is BrokerResult.Success -> "status=${result.response.status} bytes=${result.response.body.size} cached=${result.response.fromCache}"
                            is BrokerResult.Failure -> "failure=${result.code}"
                        }
                        Log.i("SourceVpnProbe", "mode=$mode $summary elapsedMs=${SystemClock.elapsedRealtime() - start}")
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun clash(enabled: Boolean) {
        val action = if (enabled) "START_CLASH" else "STOP_CLASH"
        val command = "am start -a com.github.metacubex.clash.meta.action.$action"
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)).use { it.readBytes() }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        withTimeout(20000) {
            while ((connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) != enabled) delay(100)
        }
        delay(1000) // Let link-property callbacks settle before admitting the next request.
    }

    @Test fun clashRoutesStaySourceBound(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("sourceRouteUrl")
        assumeTrue("Supply the controlled Clash fixture URL", url != null)
        marker = args.getString("sourceRouteMarker", marker)
        val expectedDefault = args.getString("sourceRouteDefault", "success")
        val expectedBypass = args.getString("sourceRouteBypass", "success")
        val expectedVpn = args.getString("sourceRouteVpn", "false").toBoolean()
        val root = File(context.cacheDir, "source-vpn-${System.nanoTime()}").apply { mkdirs() }
        val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val vpn = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        assertEquals("Fixture VPN state", expectedVpn, vpn)
        AndroidSourceNetworks(context).use { networks ->
            val settings = SourceNetworkSettings(host, networks)
            val a = Identifier("route-fixture", "default")
            val b = Identifier("route-fixture", "bypass")
            settings.setBypassVpn(b, true)
            val grants = listOf(NetworkGrant(checkNotNull(sourceOrigin(url!!)), allowPrivateAddresses = true))
            val browser = AndroidSourceBrowser(context)
            try {
                SourceBroker(File(root, "a").toPath(), browser = browser, route = settings.forSource(a)).use { default ->
                    SourceBroker(File(root, "b").toPath(), browser = browser, route = settings.forSource(b)).use { direct ->
                        val first = default.open(SourceScope(a.namespace, a.id, "test"), grants)
                        val second = direct.open(SourceScope(b.namespace, b.id, "test"), grants)
                        val request = BrokerRequest("route", url, timeoutMillis = 15000, cache = CacheMode.Disabled)
                        val results = listOf(async { first.execute(request) }, async { second.execute(request) }).awaitAll()
                        if (results[1] is BrokerResult.Failure && expectedBypass == "success") {
                            val selected = networks.route(SourceNetworkMode.BypassVpn)
                            val target = url.toHttpUrl()
                            try {
                                val addresses = selected.dns.lookup(target.host)
                                Log.i("SourceVpnFixture", "bound DNS addresses=${addresses.map { it.hostAddress }}")
                                selected.socketFactory.createSocket().use { socket ->
                                    socket.connect(InetSocketAddress(addresses.first(), target.port), 10000)
                                    Log.i("SourceVpnFixture", "bound socket connected")
                                }
                            } catch (failure: java.io.IOException) {
                                Log.i("SourceVpnFixture", "bound socket probe: ${failure.javaClass.simpleName}: ${failure.message}")
                            }
                        }
                        assertOutcome("default", expectedDefault, results[0])
                        assertOutcome("bypass", expectedBypass, results[1])
                        settings.setBypassVpn(a, true)
                        settings.setBypassVpn(b, false)
                        assertOutcome("switched-default", expectedBypass, first.execute(request))
                        assertOutcome("switched-bypass", expectedDefault, second.execute(request))
                        if (expectedBypass == "success") {
                            assertOutcome("image", "success", first.loadImage(request.copy(kind = ResourceKind.Image)))
                            assertOutcome("browser", "success", first.execute(request.copy(browser = BrowserOptions(script = "document.body.dataset.child || null"))))
                        }
                        assertEquals(0L, first.scope.accountGeneration)
                    }
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun nativeBrowserCannotSilentlyIgnoreBypass(): Unit = runBlocking {
        val root = File(context.cacheDir, "native-vpn-${System.nanoTime()}")
        val route = SourceNetworkRoute(SourceNetworkMode.BypassVpn, okhttp3.Dns.SYSTEM)
        try {
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context), route = SourceRouteProvider { route }).use { broker ->
                val session = broker.open(SourceScope("fixture", "native-bypass", "test"), listOf(NetworkGrant("https://example.com/")))
                session.configureSource("https://example.com/", true, browserRead = true)
                assertEquals(FailureCode.RouteUnsupported,
                    (session.execute(BrokerRequest("native", "https://example.com/")) as BrokerResult.Failure).code)
            }
        } finally { root.deleteRecursively() }
    }

    private fun assertOutcome(label: String, expected: String, result: BrokerResult) {
        Log.i("SourceVpnFixture", "$label expected=$expected result=$result")
        if (expected == "success") {
            assertTrue("$label: $result", result is BrokerResult.Success)
            assertFalse((result as BrokerResult.Success).response.fromCache)
            assertTrue(result.response.body.isNotEmpty())
            assertTrue("Expected fixture response", result.response.text().contains(marker))
        } else {
            assertTrue("$label: $result", result is BrokerResult.Failure)
            if (expected != "failure") assertEquals(expected, (result as BrokerResult.Failure).code.name)
        }
    }
}
