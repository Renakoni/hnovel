package indi.renakoni.nextvol.sourceexecution

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import hnovel.network.*
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import indi.renakoni.nextvol.defaultplugin.wenku8.WENKU8_USER_AGENT
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageUserAgentInstrumentedTest {
    @Test fun directCoilBuiltinProviderAndRuleHttpReachTheImageWithTheirResolvedIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val ua = request.getHeader("User-Agent").orEmpty()
                    if (ua.isBlank() || ua.startsWith("okhttp/")) return MockResponse().setResponseCode(403).setBody("fixture rejects library identity")
                    return MockResponse().setHeader("Content-Type", "image/png").setHeader("Cache-Control", "no-store").setBody(Buffer().write(png))
                }
            }
            server.start()
            val client = OkHttpClient()
            try { client.newCall(Request.Builder().url(server.url("/control")).build()).execute().use { assertEquals(403, it.code) } }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            server.takeRequest()
            val loader = SingletonImageLoader.get(context)
            for (ua in listOf(null, "Source-Explicit-UA/1.0")) {
                val request = ImageRequest.Builder(context).data(server.url("/direct").toString()).size(4, 4)
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
                ua?.let { request.httpHeaders(NetworkHeaders.Builder().add("user-agent", it).build()) }
                val result = loader.execute(request.build())
                assertTrue(result.toString(), result is SuccessResult)
                assertEquals(ua ?: DESKTOP_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            }
            // Exercise the production SourceImage -> registered built-in provider -> raw OkHttp path.
            val source = Wenku8Api { SourceNetworkRoute(SourceNetworkMode.SystemDefault, okhttp3.Dns.SYSTEM) }.use { it.id }
            val native = loader.execute(ImageRequest.Builder(context)
                .data(SourceImage(SourceBookId(source, "ua-fixture"), server.url("/native").toString(), cover = true, preferDownloaded = false))
                .size(4, 4).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
            assertTrue(native.toString(), native is SuccessResult)
            assertEquals(WENKU8_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            val root = File(context.cacheDir, "rule-image-ua-${System.nanoTime()}")
            try { SourceBroker(root.toPath()).use { broker ->
                val base = server.url("/").toString()
                val session = broker.open(SourceScope("image-ua", root.name, "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT)
                val image = session.execute(BrokerRequest("rule", server.url("/rule").toString(), kind = ResourceKind.Image))
                assertTrue(image.toString(), image is BrokerResult.Success)
                assertArrayEquals(png, (image as BrokerResult.Success).response.body)
                assertEquals(DESKTOP_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            } } finally { root.deleteRecursively() }
        }
    }
}
