package indi.dmzz_yyhyy.lightnovelreader.data.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.*
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.network.SourceNetworkMode
import hnovel.network.SourceNetworkRoute
import okhttp3.Dns
import javax.inject.Inject
import javax.inject.Singleton

/** Observes existing networks; never requests cellular or binds the main app process. */
@Singleton
class AndroidSourceNetworks @Inject constructor(@ApplicationContext context: Context) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private data class BoundNetwork(val properties: LinkProperties?, val route: SourceNetworkRoute)
    private val direct = mutableMapOf<Network, BoundNetwork>()
    private var defaultNetwork: Network? = null
    private var defaultProperties: LinkProperties? = null
    private var defaultRoute = SourceNetworkRoute.systemDefault()
    private val unavailable = SourceNetworkRoute(SourceNetworkMode.BypassVpn, Dns.SYSTEM).apply { invalidate() }
    private var observing = false

    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = retire(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!eligible(capabilities)) retire(network)
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            synchronized(this@AndroidSourceNetworks) {
                if (direct[network]?.properties?.let { it != properties } == true) retire(network)
            }
        }
    }
    private val defaults = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshDefault()
        override fun onLost(network: Network) = refreshDefault()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = refreshDefault()
    }

    @Synchronized fun route(mode: SourceNetworkMode): SourceNetworkRoute {
        if (!observing) {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_INTERNET).addCapability(NET_CAPABILITY_NOT_VPN).build(), networks)
            try { connectivity.registerDefaultNetworkCallback(defaults) }
            catch (failure: Exception) { connectivity.unregisterNetworkCallback(networks); throw failure }
            observing = true
        }
        refreshDefault()
        if (mode == SourceNetworkMode.SystemDefault) return defaultRoute
        // Enumerating also covers the interval before the first asynchronous callback arrives.
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            connectivity.getNetworkCapabilities(network)?.takeIf(::eligible)?.let { network to it }
        }
        direct.keys.filter { cached -> candidates.none { it.first == cached } }.forEach(::retire)
        val selected = candidates.minWithOrNull(compareBy<Pair<Network, NetworkCapabilities>> { rank(it.second) }
            .thenBy { it.first.networkHandle })?.first ?: return unavailable
        val properties = connectivity.getLinkProperties(selected)
        direct[selected]?.takeIf { it.properties == properties }?.let { return it.route }
        retire(selected)
        // No global Fake-IP/DoH resolver here: both DNS and sockets belong to this Network.
        return SourceNetworkRoute(mode, Dns { selected.getAllByName(it).toList() }, selected.socketFactory).also {
            direct[selected] = BoundNetwork(properties, it)
        }
    }

    @Synchronized private fun refreshDefault() {
        val network = connectivity.activeNetwork
        val properties = network?.let(connectivity::getLinkProperties)
        if (network != defaultNetwork || properties != defaultProperties) {
            defaultRoute.invalidate()
            defaultRoute = SourceNetworkRoute.systemDefault()
            defaultNetwork = network
            defaultProperties = properties
        }
    }

    /** Resolve only a route issued by this observer; never select a different network for a native child. */
    @Synchronized internal fun boundNetwork(route: SourceNetworkRoute): Network? =
        direct.entries.firstOrNull { it.value.route === route && route.available }?.key

    @Synchronized private fun retire(network: Network) {
        direct.remove(network)?.route?.invalidate()
    }

    @Synchronized override fun close() {
        if (observing) {
            connectivity.unregisterNetworkCallback(networks)
            connectivity.unregisterNetworkCallback(defaults)
            observing = false
        }
        direct.values.forEach { it.route.invalidate() }
        direct.clear()
        defaultRoute.invalidate()
        defaultRoute = SourceNetworkRoute.systemDefault()
    }

    internal companion object {
        fun eligible(capabilities: NetworkCapabilities) = capabilities.hasCapability(NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NET_CAPABILITY_NOT_VPN) && !capabilities.hasTransport(TRANSPORT_VPN)

        fun rank(capabilities: NetworkCapabilities): Int = when {
            capabilities.hasCapability(NET_CAPABILITY_VALIDATED) &&
                (capabilities.hasTransport(TRANSPORT_WIFI) || capabilities.hasTransport(TRANSPORT_ETHERNET)) -> 0
            capabilities.hasTransport(TRANSPORT_CELLULAR) -> 1
            capabilities.hasTransport(TRANSPORT_WIFI) || capabilities.hasTransport(TRANSPORT_ETHERNET) -> 2
            else -> 3
        }
    }
}
