package hnovel.network

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress

/** Host-approved exact origin. Granting a host never implicitly grants private addresses or redirects. */
@kotlinx.serialization.Serializable
data class NetworkGrant(val origin: String, val allowPrivateAddresses: Boolean = false,
    val headers: Map<String, String> = emptyMap()) {
    override fun toString() = "NetworkGrant(origin=$origin, allowPrivateAddresses=$allowPrivateAddresses)"
}

internal class NetworkPolicy(grants: List<NetworkGrant>, private val resolver: Dns) {
    private val grants = grants.associate { grant ->
        val url = grant.origin.toHttpUrl()
        require(url.encodedPath == "/" && url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty())
        origin(url) to grant.copy(headers = grant.headers.toMap())
    }

    fun check(url: HttpUrl): NetworkGrant {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw BrokerFailure(RequestStage.Permission, FailureCode.InvalidRequest)
        val grant = grants[origin(url)] ?: throw BrokerFailure(RequestStage.Permission, FailureCode.OriginDenied,
            origin(url).takeIf { it.length <= 512 && url.host.length <= 253 })
        // OkHttp may bypass Dns for literal addresses, so reject them before any connection.
        if (url.host.contains(':') || url.host.all { it.isDigit() || it == '.' }) checkAddress(InetAddress.getByName(url.host), grant)
        return grant
    }

    /** This resolver is installed in the actual OkHttp connection path; there is no second unchecked lookup. */
    fun dns(url: HttpUrl) = Dns { hostname ->
        if (hostname != url.host) throw BrokerFailure(RequestStage.Permission, FailureCode.OriginDenied)
        val grant = check(url)
        resolver.lookup(hostname).also { addresses ->
            if (addresses.isEmpty()) throw java.net.UnknownHostException()
            addresses.forEach { checkAddress(it, grant) }
        }
    }

    fun checkPeer(url: HttpUrl, address: InetAddress) = checkAddress(address, check(url))
    private fun checkAddress(address: InetAddress, grant: NetworkGrant) {
        if (!grant.allowPrivateAddresses && !isPublicAddress(address)) throw BrokerFailure(RequestStage.Permission, FailureCode.AddressDenied)
    }

    companion object {
        fun origin(url: HttpUrl) = "${url.scheme}://${if (':' in url.host) "[${url.host}]" else url.host}:${url.port}"

        /** Reject non-global ranges, including IPv4-mapped/private and IPv6 transition addresses. */
        internal fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address.map { it.toInt() and 255 }
            if (bytes.size == 4) {
                val (a, b, c) = bytes
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b in 64..127) || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                    (a == 192 && (b == 168 || b == 0 || (b == 88 && c == 99))) ||
                    (a == 198 && (b in 18..19 || (b == 51 && c == 100))) || (a == 203 && b == 0 && c == 113))
            }
            if (bytes.size != 16 || bytes[0] !in 0x20..0x3f) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x02) return false // 6to4 embeds a second destination.
            if (bytes[0] == 0x3f && bytes[1] in 0xfe..0xff) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x01 &&
                (bytes[2] <= 1 || (bytes[2] == 0x0d && bytes[3] == 0xb8))) return false
            return true
        }
    }
}
