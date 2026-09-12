package indi.dmzz_yyhyy.lightnovelreader.data.web

import hnovel.network.OriginDenial
import indi.dmzz_yyhyy.lightnovelreader.R
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError

/** Native adapters report stable errors, never response bodies, URLs or credentials. */
class SourceRequestException(val error: DiscoveryError, val denial: OriginDenial? = null) : Exception(error.name)

fun sourceFailureMessage(error: DiscoveryError): Int = when (error) {
    DiscoveryError.Unsupported -> R.string.discovery_unsupported
    DiscoveryError.AuthenticationRequired -> R.string.discovery_login_required
    DiscoveryError.PermissionDenied -> R.string.discovery_permission
    DiscoveryError.AddressDenied -> R.string.sources_address_denied
    DiscoveryError.Dns -> R.string.sources_dns_failed
    DiscoveryError.InvalidRules -> R.string.discovery_invalid_rules
    DiscoveryError.Limit -> R.string.discovery_limit
    DiscoveryError.Network -> R.string.discovery_network
    DiscoveryError.InvalidRequest -> R.string.discovery_invalid_target
    DiscoveryError.InvalidResponse -> R.string.discovery_invalid_response
    DiscoveryError.Unavailable -> R.string.discovery_unavailable
    DiscoveryError.RateLimited -> R.string.source_rate_limited
}
