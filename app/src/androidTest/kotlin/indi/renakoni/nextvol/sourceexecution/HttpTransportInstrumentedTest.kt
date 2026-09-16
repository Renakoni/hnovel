package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Same Android OkHttp dependency; owned fixture trust only, not broker policy acceptance. */
@RunWith(AndroidJUnit4::class)
class HttpTransportInstrumentedTest {
    @Test fun recordOwnedEndpointTransport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("localTransport") == "true")
        val description = URL("http://127.0.0.1:18770").openConnection().apply {
            connectTimeout = 10000; readTimeout = 10000
        }.getInputStream().use { Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject }
        val url = URL(description.getValue("url").jsonPrimitive.content)
        require(url.protocol == "https" && url.host == "127.0.0.1")
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            description.getValue("certificate").jsonPrimitive.content.byteInputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("owned-fixture", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).build()
        try {
            client.newCall(Request.Builder().url("$url/echo?client=android-okhttp").build()).execute().use { response ->
                assertTrue(response.isSuccessful)
                response.body.string()
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("transport", "Android OkHttp protocol=${response.protocol}; fixture CA only")
                })
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
