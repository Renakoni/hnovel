package indi.dmzz_yyhyy.lightnovelreader.data.web

import hnovel.network.OriginDenial
import hnovel.network.BrowserChallengeKind
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import indi.dmzz_yyhyy.lightnovelreader.R
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError

/** Native adapters report stable errors, never response bodies, URLs or credentials. */
class SourceRequestException(val error: DiscoveryError, val denial: OriginDenial? = null) : Exception(error.name)

/** Settings and verification actions report the observed failure without changing saved login state. */
fun sourceFailureMessage(error: SourceContentException): Int = when (error.code) {
    ContentError.LoginRequired -> R.string.sources_login_required
    ContentError.BrowserRequired -> when {
        error.verification?.kind == BrowserChallengeKind.Login -> R.string.sources_login_required
        error.verification != null -> R.string.sources_verification_required
        else -> R.string.sources_verification_incomplete
    }
    ContentError.PermissionDenied -> R.string.sources_permission_denied
    ContentError.AddressDenied -> R.string.sources_address_denied
    ContentError.Dns -> R.string.sources_dns_failed
    ContentError.RouteUnavailable -> R.string.sources_route_unavailable
    ContentError.RouteUnsupported -> R.string.sources_network_native
    ContentError.Network -> R.string.discovery_network
    ContentError.Unavailable -> R.string.discovery_unavailable
    ContentError.MissingCapability -> R.string.discovery_unsupported
    ContentError.UnsupportedDependency -> R.string.sources_dependency_unavailable
    else -> R.string.sources_rule_failed
}

fun sourceFailureMessage(error: DiscoveryError): Int = when (error) {
    DiscoveryError.Unsupported -> R.string.discovery_unsupported
    DiscoveryError.AuthenticationRequired -> R.string.discovery_login_required
    DiscoveryError.VerificationRequired -> R.string.discovery_verification_required
    DiscoveryError.PermissionDenied -> R.string.discovery_permission
    DiscoveryError.AddressDenied -> R.string.sources_address_denied
    DiscoveryError.Dns -> R.string.sources_dns_failed
    DiscoveryError.RouteUnavailable -> R.string.sources_route_unavailable
    DiscoveryError.RouteUnsupported -> R.string.sources_network_native
    DiscoveryError.InvalidRules -> R.string.discovery_invalid_rules
    DiscoveryError.Limit -> R.string.discovery_limit
    DiscoveryError.Network -> R.string.discovery_network
    DiscoveryError.InvalidRequest -> R.string.discovery_invalid_target
    DiscoveryError.InvalidResponse -> R.string.discovery_invalid_response
    DiscoveryError.Unavailable -> R.string.discovery_unavailable
    DiscoveryError.RateLimited -> R.string.source_rate_limited
}
