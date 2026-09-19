package hnovel.network

import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Fake-IP is a VPN routing alias. Resolve its public destination before applying address policy. */
internal class VpnDns(private val system: Dns, private val public: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = system.lookup(hostname)
        if (hostname.contains(':') || hostname.all { it.isDigit() || it == '.' }) return addresses
        return if (addresses.any(::isFakeIp)) public.lookup(hostname) else addresses
    }

    companion object {
        val Default: Dns = VpnDns(Dns.SYSTEM, PublicDns())
        private fun isFakeIp(address: InetAddress): Boolean = address.address.let {
            it.size == 4 && (it[0].toInt() and 255) == 198 && (it[1].toInt() and 255) in 18..19
        }
    }
}

/** Bootstrap addresses avoid recursively asking the VPN for the resolver's own Fake-IP. */
private class PublicDns : Dns {
    // Mainland novel APIs may return unusable overseas CDN addresses through global resolvers.
    // Prefer regional answers for the Fake-IP fallback; ordinary DNS still uses the system route.
    private val resolvers = linkedMapOf(
        "https://dns.alidns.com/resolve" to listOf("223.5.5.5", "223.6.6.6"),
        "https://doh.pub/dns-query" to listOf("1.12.12.12", "120.53.53.53"),
        "https://dns.google/resolve" to listOf("8.8.8.8", "8.8.4.4"),
        "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1"))
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .dns { host -> resolvers.entries.single { it.key.toHttpUrl().host == host }.value.map(InetAddress::getByName) }
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override fun lookup(hostname: String): List<InetAddress> {
        for (endpoint in resolvers.keys) {
            try {
                val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("name", hostname).addQueryParameter("type", "A").build()
                client.newCall(Request.Builder().url(url).header("Accept", "application/dns-json").build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("DNS service unavailable")
                    val input = response.body.source()
                    if (input.request(65537)) throw IOException("DNS response too large")
                    val bytes = input.readByteArray()
                    val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    val records = json["Answer"] as? JsonArray ?: continue
                    val addresses = records.mapNotNull { record ->
                        val value = record.jsonObject
                        val literal = value["data"]?.jsonPrimitive?.content.orEmpty()
                        if (value["type"]?.jsonPrimitive?.intOrNull != 1 || !literal.matches(Regex("[0-9.]{7,15}"))) null
                        else InetAddress.getByAddress(hostname, InetAddress.getByName(literal).address)
                    }.distinct()
                    if (addresses.isNotEmpty()) return addresses
                }
            } catch (_: IOException) { /* Try the next resolver through the same system route. */ }
              catch (_: IllegalArgumentException) { /* Invalid DNS data is not a connection target. */ }
        }
        throw UnknownHostException("Public DNS could not resolve the VPN alias")
    }
}
