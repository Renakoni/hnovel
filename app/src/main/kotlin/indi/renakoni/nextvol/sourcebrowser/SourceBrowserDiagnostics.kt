package indi.renakoni.nextvol.sourcebrowser

import hnovel.network.BrokerRequest
import hnovel.network.BrowserOptions
import hnovel.network.CacheMode
import hnovel.network.RequestReason

/** Explains an already-selected mediated route; never participates in the routing decision. */
internal fun mediatedBrowserReason(request: BrokerRequest, options: BrowserOptions, nativeSupported: Boolean): RequestReason = when {
    options.verificationCode -> RequestReason.VerificationDocument
    options.html != null -> RequestReason.SuppliedHtml
    !options.interactive && !options.nativeWebsite -> RequestReason.BrowserRendering
    !nativeSupported -> RequestReason.NativeUnavailable
    request.method != "GET" -> RequestReason.HttpMethod
    !request.followRedirects -> RequestReason.RedirectsDisabled
    request.responseAsHex -> RequestReason.HexResponse
    request.cache == CacheMode.Only -> RequestReason.CacheOnly
    request.headers.keys.any { it.equals("Cookie", true) } -> RequestReason.ExplicitCookie
    else -> RequestReason.MediatedFallback
}
