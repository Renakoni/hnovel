package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8

import hnovel.network.SourceNetworkMode
import hnovel.network.SourceNetworkRoute
import io.ktor.client.HttpClient
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class Wenku8RouteUnavailableException : IOException("Source network is unavailable")

/** Trusted built-in transport. Parsing, request headers and business retries remain in Wenku8Api. */
internal class Wenku8HttpClients(private val routes: (Identifier) -> SourceNetworkRoute,
    private val contentClient: (OkHttpClient) -> HttpClient) : AutoCloseable {
    private data class Key(val source: Identifier, val route: SourceNetworkRoute)
    private class Client(val http: HttpClient, val transport: OkHttpClient) : AutoCloseable {
        var invalidation: AutoCloseable? = null
        override fun close() {
            invalidation?.close()
            http.coroutineContext.cancel()
            transport.dispatcher.cancelAll()
            http.close()
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
        }
    }

    private val clients = mutableMapOf<Key, Client>()
    @Volatile private var closed = false

    suspend fun <T> request(source: Identifier, block: suspend (HttpClient) -> T): T =
        onRoute(source) { block(it.http) }

    /** Keep the old image headers and OkHttp defaults; document cookies are not image credentials. */
    suspend fun image(source: Identifier, url: String, headers: Map<String, String>): ByteArray = onRoute(source) { client ->
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        suspendCancellableCoroutine { continuation ->
            val call = client.transport.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw IOException("Image request failed (${it.code})")
                            it.body?.bytes() ?: throw IOException("Image response is empty")
                        }
                        continuation.resume(bytes)
                    } catch (failure: Exception) { continuation.resumeWithException(failure) }
                }
            })
        }
    }

    private suspend fun <T> onRoute(source: Identifier, block: suspend (Client) -> T): T {
        currentCoroutineContext().ensureActive()
        // Resolve only once; all Ktor retries and both clients' redirects retain this generation.
        val route = routes(source)
        val key = Key(source, route)
        val client = synchronized(this) {
            check(!closed) { "Wenku8 client is closed" }
            available(route)
            clients[key] ?: create(route).also { client ->
                clients[key] = client
                client.invalidation = route.onInvalidated { retire(key, client) }
            }
        }
        return try {
            available(route)
            block(client).also { currentCoroutineContext().ensureActive(); available(route) }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            available(route)
            throw failure
        }
    }

    private fun create(route: SourceNetworkRoute): Client {
        val transport = OkHttpClient.Builder().dns(route.dns).socketFactory(route.socketFactory)
            .apply { if (route.mode == SourceNetworkMode.BypassVpn) proxy(Proxy.NO_PROXY) }
            .addInterceptor { chain ->
                available(route)
                check(!closed) { "Wenku8 client is closed" }
                chain.proceed(chain.request())
            }.build()
        return Client(contentClient(transport), transport)
    }

    private fun available(route: SourceNetworkRoute) {
        if (!route.available) throw Wenku8RouteUnavailableException()
    }

    private fun retire(key: Key, client: Client) {
        val removed = synchronized(this) {
            if (clients[key] === client) { clients.remove(key); true } else false
        }
        if (removed) client.close()
    }

    override fun close() {
        val retired = synchronized(this) {
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        retired.forEach { it.close() }
    }
}
