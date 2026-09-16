package indi.renakoni.nextvol.sourceexecution

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.network.*
import indi.renakoni.nextvol.data.web.AndroidSourceNetworks
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Owned secure-context fixtures. External Clash checks require an explicit fixture URL. */
@RunWith(AndroidJUnit4::class)
class NativeBrowserRouteInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val options = BrowserOptions(script = "window.answer || null")
    private fun supported() = assumeTrue("Native routing requires API 28+ and proxy override", AndroidSourceBrowser.supportsVpnBypass(context))

    private fun fixture(pulses: AtomicInteger = AtomicInteger()): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val response = MockResponse().setHeader("Cache-Control", "no-store")
                if (path == "/redirect") return response.setResponseCode(302).setHeader("Location", "/")
                if (path == "/pulse") pulses.incrementAndGet()
                val asset = when (path) { "/" -> "index.html"; "/page.js", "/worker.js", "/sw.js" -> path.drop(1); else -> null }
                return if (asset != null) response.setHeader("Content-Type", if (asset.endsWith(".js")) "application/javascript" else "text/html")
                    .setBody(instrumentation.context.assets.open(asset).bufferedReader().use { it.readText() })
                else response.setHeader("Content-Type", if (path == "/frame") "text/html" else "text/plain").setBody(path.drop(1))
            }
        }
        start()
    }

    private fun result(response: BrokerResult): JsonObject {
        assertTrue(response.toString(), response is BrokerResult.Success)
        val body = (response as BrokerResult.Success).response
        assertFalse(body.fromCache)
        assertEquals(ResponseKind.BrowserDocument, body.kind)
        return Json.parseToJsonElement(body.text()).jsonObject.also { assertFalse(it.toString(), "error" in it) }
    }

    private fun assertChildren(value: JsonObject) {
        assertEquals(listOf("fetch", "xhr", "frame", "worker-network", "sw-network"),
            value.getValue("children").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun modeChangesKeepPersistentAccountsAndAllNativeChildren(): Unit = runBlocking {
        supported()
        fixture().use { server ->
            val url = "http://localhost:${server.port}/"
            val root = File(context.cacheDir, "native-route-${UUID.randomUUID()}")
            try { AndroidSourceNetworks(context).use { networks ->
                var mode = SourceNetworkMode.SystemDefault
                SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context, networks), route = SourceRouteProvider { networks.route(mode) }).use { broker ->
                    val a = broker.open(SourceScope("native-route", "a-${root.name}", "test"), listOf(NetworkGrant(url, true)))
                    val b = broker.open(SourceScope("native-route", "b-${root.name}", "test"), listOf(NetworkGrant(url, true)))
                    listOf(a, b).forEach { it.configureSource(url, true, browserRead = true) }
                    suspend fun read(session: SourceSession, path: String) = result(session.execute(BrokerRequest("fixture", url + path,
                        browser = options, timeoutMillis = 30000)))
                    try {
                        val first = read(a, "?set=A"); assertChildren(first)
                        mode = SourceNetworkMode.BypassVpn
                        val other = read(b, "?set=B"); assertChildren(other)
                        assertEquals(JsonNull, other.getValue("previous").jsonObject["value"])
                        assertFalse(other.getValue("previous").jsonObject.getValue("cookie").jsonPrimitive.content.contains("persistent=A"))
                        val resumed = read(a, "redirect"); assertChildren(resumed)
                        assertEquals("A", resumed.getValue("value").jsonPrimitive.content)
                        assertTrue(resumed.getValue("cookie").jsonPrimitive.content.contains("persistent=A"))
                        mode = SourceNetworkMode.SystemDefault
                        val restored = read(b, ""); assertChildren(restored)
                        assertEquals("B", restored.getValue("value").jsonPrimitive.content)
                        assertTrue(restored.getValue("cookie").jsonPrimitive.content.contains("persistent=B"))
                        assertEquals(0L, a.scope.accountGeneration)
                        assertNull(context.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess)
                    } finally { a.clearAccount(); b.clearAccount() }
                }
            } } finally { root.deleteRecursively() }
        }
    }

    @Test fun retiringAnIdleRouteStopsItsServiceWorkerAndNewRequestsFailClosed(): Unit = runBlocking { idleRetirement(false) }

    @Test fun closingASourceStopsItsIdleServiceWorker(): Unit = runBlocking { idleRetirement(true) }

    private suspend fun idleRetirement(closeSession: Boolean) {
        supported()
        val pulses = AtomicInteger()
        fixture(pulses).use { server ->
            val url = "http://localhost:${server.port}/"
            val root = File(context.cacheDir, "native-retire-${UUID.randomUUID()}")
            try { AndroidSourceNetworks(context).use { networks ->
                val route = networks.route(SourceNetworkMode.BypassVpn)
                SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context, networks), route = SourceRouteProvider { route }).use { broker ->
                    val session = broker.open(SourceScope("native-route", root.name, "test"), listOf(NetworkGrant(url, true)))
                    session.configureSource(url, true, browserRead = true)
                    try {
                        assertTrue(result(session.execute(BrokerRequest("pulse", url + "?pulse=1", browser = options, timeoutMillis = 30000)))
                            .getValue("pulse").jsonPrimitive.boolean)
                        val completedAt = pulses.get()
                        withTimeout(10000) { while (pulses.get() < completedAt + 2) delay(100) }
                        if (closeSession) session.close() else route.invalidate()
                        delay(1500)
                        val stoppedAt = pulses.get()
                        delay(1500)
                        assertEquals(stoppedAt, pulses.get())
                        val retired = runCatching { session.execute(BrokerRequest("retired", url, browser = options)) }
                        if (closeSession) assertTrue(retired.exceptionOrNull() is CancellationException)
                        else assertEquals(FailureCode.RouteUnavailable, (retired.getOrThrow() as BrokerResult.Failure).code)
                    } finally { session.clearAccount() }
                }
            } } finally { root.deleteRecursively() }
        }
    }

    @Test fun cancellationKeepsPersistentStateButAccountRetirementClearsIt(): Unit = runBlocking {
        supported()
        val pulses = AtomicInteger()
        fixture(pulses).use { server ->
            val url = "http://localhost:${server.port}/"
            val root = File(context.cacheDir, "native-cancel-${UUID.randomUUID()}")
            try { AndroidSourceNetworks(context).use { networks ->
                SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context, networks),
                    route = SourceRouteProvider { networks.route(SourceNetworkMode.BypassVpn) }).use { broker ->
                    val scope = SourceScope("native-route", root.name, "test")
                    val grants = listOf(NetworkGrant(url, true))
                    val session = broker.open(scope, grants).apply { configureSource(url, true, browserRead = true) }
                    var next: SourceSession? = null
                    suspend fun awaitPulses(after: Int) = withTimeout(30000) { while (pulses.get() < after + 2) delay(100) }
                    try {
                        // Establish completed state; a cancelled page's unflushed writes are not durable.
                        assertChildren(result(session.execute(BrokerRequest("save", url + "?set=A", browser = options, timeoutMillis = 30000))))
                        val pending = launch { session.execute(BrokerRequest("hold", url + "?pulse=1",
                            browser = BrowserOptions(script = "null"), timeoutMillis = 30000)) }
                        awaitPulses(0)
                        withTimeout(10000) { pending.cancelAndJoin() }
                        // The next request waits behind the old native process's shutdown fence.
                        val restored = result(session.execute(BrokerRequest("read", url, browser = options, timeoutMillis = 30000)))
                        assertChildren(restored)
                        assertEquals("A", restored.getValue("value").jsonPrimitive.content)
                        assertTrue(restored.getValue("cookie").jsonPrimitive.content.contains("persistent=A"))
                        assertEquals(0L, session.scope.accountGeneration)
                        val stopped = pulses.get()
                        delay(1000)
                        assertEquals(stopped, pulses.get())

                        val retiring = async { runCatching { session.execute(BrokerRequest("retire", url + "?pulse=1",
                            browser = BrowserOptions(script = "null"), timeoutMillis = 30000)) } }
                        awaitPulses(stopped)
                        withContext(Dispatchers.IO) { session.clearAccount() }
                        assertTrue(withTimeout(10000) { retiring.await() }.exceptionOrNull() is CancellationException)
                        next = broker.open(scope.copy(accountGeneration = 1), grants).apply { configureSource(url, true, browserRead = true) }
                        val fresh = result(next.execute(BrokerRequest("new-account", url, browser = options, timeoutMillis = 30000)))
                        assertChildren(fresh)
                        assertEquals(JsonNull, fresh["value"])
                        assertFalse(fresh.getValue("cookie").jsonPrimitive.content.contains("persistent=A"))
                    } finally { session.clearAccount(); next?.clearAccount() }
                }
            } } finally { root.deleteRecursively() }
        }
    }

    /** Opt-in only: changes the prepared Android Clash fixture, finishing with it stopped. */
    @Test fun continuousBypassSurvivesClashStopAndRestart(): Unit = runBlocking {
        supported()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in to controlling the prepared Clash fixture", args.getString("nativeRouteCycle") == "true")
        val url = checkNotNull(args.getString("nativeRouteUrl"))
        val root = File(context.cacheDir, "native-cycle-${UUID.randomUUID()}")
        try { AndroidSourceNetworks(context).use { networks ->
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context, networks),
                route = SourceRouteProvider { networks.route(SourceNetworkMode.BypassVpn) }).use { broker ->
                val session = broker.open(SourceScope("native-route", root.name, "test"), listOf(NetworkGrant(checkNotNull(sourceOrigin(url)), true)))
                session.configureSource(url, true, browserRead = true)
                try {
                    for ((index, vpn) in listOf(false, true, false, true, false).withIndex()) {
                        clash(vpn)
                        val value = result(session.execute(BrokerRequest("cycle", url + if (index == 0) "?set=kept" else "",
                            browser = options, timeoutMillis = 30000)))
                        assertChildren(value)
                        assertEquals("kept", value.getValue("value").jsonPrimitive.content)
                        assertTrue(value.getValue("cookie").jsonPrimitive.content.contains("persistent=kept"))
                        assertEquals(0L, session.scope.accountGeneration)
                    }
                } finally { session.clearAccount() }
            }
        } } finally { clash(false); root.deleteRecursively() }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
        .executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    private suspend fun clash(enabled: Boolean) {
        shell("am start -a com.github.metacubex.clash.meta.action.${if (enabled) "START_CLASH" else "STOP_CLASH"}")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        withTimeout(20000) {
            while ((connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) != enabled) delay(100)
        }
        delay(1000)
    }

    /** Opt-in only: disconnects and restores the emulator's initially active Wi-Fi. */
    @Test fun physicalNetworkLossStopsNativeBackgroundWork(): Unit = runBlocking {
        supported()
        assumeTrue("Opt in to Wi-Fi loss", InstrumentationRegistry.getArguments().getString("nativeNetworkLoss") == "true")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun wifiActive() = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        assumeTrue("Start with Wi-Fi active and Android VPN stopped", wifiActive())
        val pulses = AtomicInteger()
        fixture(pulses).use { server ->
            val url = "http://localhost:${server.port}/"
            val root = File(context.cacheDir, "native-loss-${UUID.randomUUID()}")
            try { AndroidSourceNetworks(context).use { networks ->
                var route = networks.route(SourceNetworkMode.BypassVpn)
                SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context, networks), route = SourceRouteProvider { route }).use { broker ->
                    val session = broker.open(SourceScope("native-route", root.name, "test"), listOf(NetworkGrant(url, true)))
                    session.configureSource(url, true, browserRead = true)
                    try {
                        result(session.execute(BrokerRequest("pulse", url + "?pulse=1&set=kept", browser = options, timeoutMillis = 30000)))
                        val completedAt = pulses.get()
                        withTimeout(10000) { while (pulses.get() < completedAt + 2) delay(100) }
                        shell("svc wifi disable")
                        withTimeout(15000) { while (route.available) delay(100) }
                        delay(1500)
                        val stopped = pulses.get()
                        delay(1500)
                        assertEquals(stopped, pulses.get())
                        assertEquals(FailureCode.RouteUnavailable,
                            (session.execute(BrokerRequest("lost", url, browser = options)) as BrokerResult.Failure).code)
                        shell("svc wifi enable")
                        // Validation and Private DNS publish more than one link-properties update.
                        withTimeout(30000) {
                            while (true) {
                                if (!wifiActive()) { delay(100); continue }
                                val candidate = networks.route(SourceNetworkMode.BypassVpn)
                                delay(1000)
                                if (candidate.available && candidate === networks.route(SourceNetworkMode.BypassVpn)) {
                                    route = candidate
                                    break
                                }
                            }
                        }
                        val resumed = result(session.execute(BrokerRequest("resume", url, browser = options, timeoutMillis = 30000)))
                        assertChildren(resumed)
                        assertEquals("kept", resumed.getValue("value").jsonPrimitive.content)
                        assertTrue(resumed.getValue("cookie").jsonPrimitive.content.contains("persistent=kept"))
                    } finally { shell("svc wifi enable"); session.clearAccount() }
                }
            } } finally { root.deleteRecursively() }
        }
    }

    @Test fun preparedClashRoutesAllNativeChildren(): Unit = runBlocking {
        supported()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("nativeRouteUrl")
        assumeTrue("Opt in with the prepared HTTPS fixture URL", url != null)
        val defaultFails = args.getString("nativeRouteDefault") == "failure"
        val root = File(context.cacheDir, "native-clash-${UUID.randomUUID()}")
        try { AndroidSourceNetworks(context).use { networks ->
            var mode = SourceNetworkMode.SystemDefault
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context, networks), route = SourceRouteProvider { networks.route(mode) }).use { broker ->
                val session = broker.open(SourceScope("native-route", root.name, "test"), listOf(NetworkGrant(checkNotNull(sourceOrigin(url!!)), true)))
                session.configureSource(url, true, browserRead = true)
                try {
                    for (selected in listOf(SourceNetworkMode.SystemDefault, SourceNetworkMode.BypassVpn, SourceNetworkMode.SystemDefault)) {
                        mode = selected
                        val response = session.execute(BrokerRequest("clash", url, browser = options, timeoutMillis = 20000))
                        Log.i("NativeRouteFixture", "mode=$mode result=${response::class.simpleName}")
                        if (mode == SourceNetworkMode.SystemDefault && defaultFails) assertTrue(response.toString(), response is BrokerResult.Failure)
                        else assertChildren(result(response))
                    }
                    assertEquals(0L, session.scope.accountGeneration)
                } finally { session.clearAccount() }
            }
        } } finally { root.deleteRecursively() }
    }

    @Test fun preparedClashCannotResolveNativeBypassChildrenThroughItsDns(): Unit = runBlocking {
        supported()
        val url = InstrumentationRegistry.getArguments().getString("nativeDnsUrl")
        assumeTrue("Opt in with the prepared HTTPS DNS fixture URL", url != null)
        val root = File(context.cacheDir, "native-dns-${UUID.randomUUID()}")
        try { AndroidSourceNetworks(context).use { networks ->
            var mode = SourceNetworkMode.SystemDefault
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context, networks), route = SourceRouteProvider { networks.route(mode) }).use { broker ->
                val session = broker.open(SourceScope("native-route", root.name, "test"), listOf(NetworkGrant(checkNotNull(sourceOrigin(url!!)), true)))
                session.configureSource(url, true, browserRead = true)
                try {
                    for (selected in SourceNetworkMode.entries) {
                        mode = selected
                        val value = result(session.execute(BrokerRequest("dns", url + "?dns=1", browser = options, timeoutMillis = 20000)))
                        assertEquals(mode == SourceNetworkMode.SystemDefault, value.getValue("dns").jsonPrimitive.boolean)
                    }
                } finally { session.clearAccount() }
            }
        } } finally { root.deleteRecursively() }
    }
}
