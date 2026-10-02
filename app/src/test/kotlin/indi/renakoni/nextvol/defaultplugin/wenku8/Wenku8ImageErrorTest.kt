package indi.renakoni.nextvol.defaultplugin.wenku8

import android.app.Application
import hnovel.network.SourceNetworkMode
import hnovel.network.SourceNetworkRoute
import indi.renakoni.nextvol.data.image.SourceImageHttpException
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class Wenku8ImageErrorTest {
    @Test fun imageHttpRejectionsKeepStatusAndRetryAfterWithoutRetryingInternally() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            Wenku8HttpClients({ SourceNetworkRoute(SourceNetworkMode.SystemDefault, okhttp3.Dns.SYSTEM) }) { transport ->
                io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) { engine { preconfigured = transport } }
            }.use { clients ->
                for ((index, status) in listOf(403, 429).withIndex()) {
                    server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "3")
                        .setBody("private rejection body"))
                    val failure = runCatching { clients.image(Identifier("fixture", "image"),
                        server.url("/cover").toString(), emptyMap()) }.exceptionOrNull()
                    assertTrue(failure.toString(), failure is SourceImageHttpException)
                    val http = failure as SourceImageHttpException
                    assertEquals(status, http.httpStatus)
                    assertEquals(if (status == 429) 3000L else null, http.retry?.retryAfterMillis)
                    assertFalse(http.message.orEmpty().contains("private"))
                    assertEquals(index + 1, server.requestCount)
                }
            }
        }
    }
}
