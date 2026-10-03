package indi.renakoni.nextvol.data.bangumi

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.xbill.DNS.Type
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Official API only. The search mirror keeps its separate, credential-free client. */
@Singleton
class BangumiNetwork internal constructor(
    private val bootstrap: OkHttpClient,
    private val configEndpoints: List<HttpUrl>,
    private val dnsEndpoint: HttpUrl,
    private val secureClient: (ByteArray, Dns) -> OkHttpClient,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Inject constructor() : this(bootstrapClient(), CONFIG_ENDPOINTS, DNS_ENDPOINT, tlsClients())

    private data class Config(val bytes: ByteArray, val expires: Long)
    private data class Route(val client: OkHttpClient, val expires: Long)
    private val mutex = Mutex()
    private val doh = BangumiDoh()
    private var config: Config? = null
    private var route: Route? = null

    suspend fun client(): OkHttpClient = try {
        withTimeout(12_000) {
            mutex.withLock {
                route?.takeIf { now() < it.expires }?.let { return@withLock it.client }
                val ech = config?.takeIf { now() < it.expires } ?: loadConfig().also { config = it }
                val resolver = secureClient(ech.bytes, fixedDns(mapOf(DNS_HOST to DNS_ADDRESSES)))
                val answers = try { coroutineScope {
                    listOf(Type.A, Type.AAAA).map { type -> async {
                        try { doh.query(resolver, dnsEndpoint, API_HOST, type) }
                        catch (_: IOException) { null }
                    } }.awaitAll().filterNotNull()
                } } finally { resolver.connectionPool.evictAll() }
                val addresses = answers.flatMap { it.addresses() }.distinct()
                if (addresses.isEmpty()) {
                    config = null // A rejected ECH key must be refreshed on the next attempt.
                    throw UnknownHostException("Bangumi DNS unavailable")
                }
                val expires = minOf(ech.expires, now() + answers.filter { it.addresses().isNotEmpty() }.minOf { it.ttl } * 1000)
                val client = secureClient(ech.bytes, fixedDns(mapOf(API_HOST to addresses)))
                route?.client?.connectionPool?.evictAll()
                route = Route(client, expires)
                client
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw InterruptedIOException("Bangumi DNS timed out")
    }

    suspend fun invalidate(client: OkHttpClient) = mutex.withLock {
        if (route?.client === client) {
            route = null
            config = null
            client.connectionPool.evictAll()
        }
    }

    private suspend fun loadConfig(): Config {
        for (endpoint in configEndpoints) {
            try {
                val answer = doh.query(bootstrap, endpoint, "cloudflare-ech.com", Type.HTTPS)
                return Config(answer.echConfig(), now() + answer.ttl * 1000)
            } catch (_: IOException) { currentCoroutineContext().ensureActive() }
        }
        throw IOException("Bangumi ECH configuration unavailable")
    }

    companion object {
        internal const val API_HOST = "api.bgm.tv"
        private const val DNS_HOST = "cloudflare-dns.com"
        private val DNS_ENDPOINT = "https://cloudflare-dns.com/dns-query".toHttpUrl()
        private val CONFIG_ENDPOINTS = listOf("https://doh.pub/dns-query", "https://dns.alidns.com/dns-query").map { it.toHttpUrl() }
        // Bootstrap hints belong to the resolver operators. TLS still verifies their DNS names.
        private val DNS_ADDRESSES = addresses("104.16.248.249", "104.16.249.249", "2606:4700::6810:f8f9", "2606:4700::6810:f9f9")
        private fun addresses(vararg values: String) = values.map(InetAddress::getByName)
        internal fun fixedDns(hosts: Map<String, List<InetAddress>>): Dns = Dns { host ->
            // OkHttp also uses this DNS for an explicitly configured HTTP proxy's hostname.
            hosts[host] ?: Dns.SYSTEM.lookup(host)
        }
        private fun bootstrapClient() = OkHttpClient.Builder()
            .dns(fixedDns(mapOf("doh.pub" to addresses("1.12.12.12", "120.53.53.53"),
                "dns.alidns.com" to addresses("223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1"))))
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()

        private fun tlsClients(): (ByteArray, Dns) -> OkHttpClient {
            val tls by lazy { BangumiEchTls() }
            return { config, dns ->
                OkHttpClient.Builder().dns(dns).sslSocketFactory(tls.socketFactory(config), tls.trustManager)
                    .followRedirects(false).followSslRedirects(false)
                    .connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.SECONDS).build()
            }
        }
    }
}
