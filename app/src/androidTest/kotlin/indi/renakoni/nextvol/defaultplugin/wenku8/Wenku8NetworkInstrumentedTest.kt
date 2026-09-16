package indi.renakoni.nextvol.defaultplugin.wenku8

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.getOrElse
import hnovel.network.SourceNetworkMode
import indi.renakoni.nextvol.data.web.AndroidSourceNetworks
import indi.renakoni.nextvol.data.web.SourceNetworkSettings
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

/** Real Android Network DNS/socket factories; external VPN checks require an owned fixture URL. */
@RunWith(AndroidJUnit4::class)
class Wenku8NetworkInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private fun vpnActive() = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    @Test fun savedBypassWorksWithoutVpnForDocumentsCoversAndIllustrations(): Unit = runBlocking {
        assertFalse("This fixture verifies the always-enabled switch without an Android VPN", vpnActive())
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/image")
                    MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                else MockResponse().setHeader("Content-Type", "text/html; charset=GBK")
                    .setBody(Buffer().write("<p>文库 • ・ 〜</p>".toByteArray(Charset.forName("GB18030"))))
            }
            server.start()
            val root = File(context.cacheDir, "wenku8-routing-${UUID.randomUUID()}")
            val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
            try { AndroidSourceNetworks(context).use { networks ->
                val settings = SourceNetworkSettings(host, networks)
                Wenku8Api { id -> settings.forSource(id).snapshot() }.use { api ->
                    val documentUrl = "http://localhost:${server.port}/document"
                    val imageUrl = "http://localhost:${server.port}/image"
                    for (bypass in listOf(true, false, true)) {
                        settings.setBypassVpn(api.id, bypass)
                        val mode = if (bypass) SourceNetworkMode.BypassVpn else SourceNetworkMode.SystemDefault
                        assertEquals(mode, SourceNetworkSettings(host, networks).mode(api.id))
                        assertEquals("文库 • ・ 〜", api.getWithWenku8Cookie(documentUrl).getOrElse { throw it }.text())
                        for (cover in listOf(true, false)) {
                            val bytes = api.getImage("book", imageUrl, cover).getOrElse { throw it.throwable!! }
                            assertArrayEquals(png, bytes)
                            assertEquals(2, BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width)
                        }
                        assertNull("The application process must stay unbound", connectivity.boundNetworkForProcess)
                    }
                    // Keep the saved preference enabled when the client is rebuilt.
                    Wenku8Api { id -> SourceNetworkSettings(host, networks).forSource(id).snapshot() }.use { reopened ->
                        assertEquals("文库 • ・ 〜", reopened.getWithWenku8Cookie(documentUrl).getOrElse { throw it }.text())
                    }
                    val retired = networks.route(SourceNetworkMode.BypassVpn)
                    retired.invalidate()
                    Wenku8Api { retired }.use { unavailable ->
                        assertTrue(unavailable.getWithWenku8Cookie(documentUrl).component2() is Wenku8RouteUnavailableException)
                        assertTrue(unavailable.getImage("book", imageUrl, true).component2()?.throwable is Wenku8RouteUnavailableException)
                    }
                    assertEquals(10, server.requestCount)
                }
            } } finally { root.deleteRecursively() }
        }
    }

    /** Does not change any VPN/proxy configuration; use the already prepared port-reject fixture. */
    @Test fun preparedClashKeepsBuiltinDocumentsAndImagesOnTheirSelectedRoute(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("wenkuRouteUrl")
        assumeTrue("Supply the owned HTTP fixture URL", url != null)
        val expectedVpn = args.getString("wenkuRouteVpn", "false").toBoolean()
        assertEquals("Prepared Android VPN state", expectedVpn, vpnActive())
        val root = File(context.cacheDir, "wenku8-clash-${UUID.randomUUID()}")
        val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
        try { AndroidSourceNetworks(context).use { networks ->
            val settings = SourceNetworkSettings(host, networks)
            Wenku8Api { id -> settings.forSource(id).snapshot() }.use { api ->
                for (bypass in listOf(false, true, false, true)) {
                    settings.setBypassVpn(api.id, bypass)
                    val document = withTimeout(30000) { api.getWithWenku8Cookie(url!!) }
                    val reached = document.getOrElse { null }?.text()?.contains("hnovel-route-fixture") == true
                    val image = withTimeout(30000) { api.getImage("fixture", url!!.trimEnd('/') + "/image", true) }
                    val decoded = image.getOrElse { null }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    assertEquals("Document: bypass=$bypass, VPN=$expectedVpn", !expectedVpn || bypass, reached)
                    assertEquals("Image: bypass=$bypass, VPN=$expectedVpn", !expectedVpn || bypass, decoded != null)
                    assertNull(connectivity.boundNetworkForProcess)
                    Log.i("Wenku8RouteFixture", "vpn=$expectedVpn bypass=$bypass document=$reached image=${decoded != null}")
                }
                assertEquals(SourceNetworkMode.BypassVpn, SourceNetworkSettings(host, networks).mode(api.id))
            }
        } } finally { root.deleteRecursively() }
    }
}
