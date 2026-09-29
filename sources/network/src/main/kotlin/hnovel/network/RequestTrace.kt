package hnovel.network

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local evidence only; neither IDs nor events are sent to the requested website. */
@Serializable enum class RequestEvidence { Selected, HeadersResolved, TransportHeaders, WebViewSettings, Completed, Cancelled }
@Serializable enum class RequestPath { Http, Browser, NativeWebView, MediatedWebView, Inline, Cache }
@Serializable enum class RequestReason { DefaultHttp, ExplicitHttp, ExplicitBrowser, SourceBrowserRead,
    NativeSourceRead, NativeWebsite, InteractiveBrowser, MediatedFallback, WebCookieDefaults, CookieBootstrap,
    VerificationDocument, SuppliedHtml, BrowserRendering, NativeUnavailable, HttpMethod, RedirectsDisabled,
    HexResponse, CacheOnly, ExplicitCookie }
@Serializable enum class UserAgentSource { SessionDefault, OriginGrant, AccountLogin, RequestHeaders,
    TransportDefault, WebViewDefault }
@Serializable enum class UserAgentFamily { Chromium, Firefox, Safari, OkHttp, Other, Unknown }
@Serializable enum class UserAgentPlatform { Windows, Android, Mac, Linux, Other, Unknown }
@Serializable enum class UserAgentMetadataStatus { ProviderDefault, Unsupported, UnhandledUserAgent, Applied, Rejected }

/** No raw UA, hashes, URL, headers, cookies, script text or provider-supplied strings. */
@Serializable data class UserAgentSummary(val family: UserAgentFamily, val platform: UserAgentPlatform,
    val majorVersion: Int? = null) {
    companion object {
        fun from(value: String?): UserAgentSummary {
            if (value == null) return UserAgentSummary(UserAgentFamily.Unknown, UserAgentPlatform.Unknown)
            val family = when {
                "Chrome/" in value || "Chromium/" in value -> UserAgentFamily.Chromium
                "Firefox/" in value -> UserAgentFamily.Firefox
                "Safari/" in value -> UserAgentFamily.Safari
                value.startsWith("okhttp/", true) -> UserAgentFamily.OkHttp
                else -> UserAgentFamily.Other
            }
            val platform = when {
                "Windows NT" in value -> UserAgentPlatform.Windows
                "Android" in value -> UserAgentPlatform.Android
                "Macintosh" in value -> UserAgentPlatform.Mac
                "Linux" in value -> UserAgentPlatform.Linux
                else -> UserAgentPlatform.Other
            }
            val marker = when (family) {
                UserAgentFamily.Chromium -> "(?:Chrome|Chromium)"
                UserAgentFamily.Firefox -> "Firefox"
                UserAgentFamily.Safari -> "Version"
                UserAgentFamily.OkHttp -> "okhttp"
                else -> null
            }
            val major = marker?.let { Regex("$it/([0-9]{1,4})(?:[.; ]|$)").find(value)
                ?.groupValues?.get(1)?.toIntOrNull() }
            return UserAgentSummary(family, platform, major)
        }
    }
}

/** Settings readback and setter outcome, not JS/client-hint or server observation. */
@Serializable data class WebViewUserAgentDiagnostic(val metadata: UserAgentMetadataStatus,
    val userAgent: UserAgentSummary, val matchesRequested: Boolean? = null)

/** userAgentSources is ordered from lowest priority to winner; request-level inputs are already merged. */
@Serializable data class RequestDiagnostic(val requestId: String, val parentRequestId: String? = null,
    val evidence: RequestEvidence, val path: RequestPath? = null, val reason: RequestReason? = null,
    val userAgentSources: List<UserAgentSource> = emptyList(), val userAgent: UserAgentSummary? = null,
    val webView: WebViewUserAgentDiagnostic? = null, val attempt: Int? = null, val hop: Int? = null,
    val status: Int? = null, val failure: FailureCode? = null)

fun interface RequestTrace {
    fun record(event: RequestDiagnostic)
    companion object { val None = RequestTrace {} }
}

internal data class HttpRequestObservation(val observation: RequestObservation, val attempt: Int, val hop: Int)

/** Host-owned context. Explicitly carried into session work and captured by trusted Binder callbacks. */
class RequestObservation internal constructor(private val trace: RequestTrace, parent: RequestObservation?) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestObservation>
    private val id = UUID.randomUUID().toString()
    private val parentId = parent?.id

    fun record(evidence: RequestEvidence, path: RequestPath? = null, reason: RequestReason? = null,
        userAgentSources: List<UserAgentSource> = emptyList(), userAgent: UserAgentSummary? = null,
        webView: WebViewUserAgentDiagnostic? = null, attempt: Int? = null, hop: Int? = null,
        status: Int? = null, failure: FailureCode? = null) {
        // A broken diagnostic consumer must never turn a successful request into a failure.
        runCatching { trace.record(RequestDiagnostic(id, parentId, evidence, path, reason, userAgentSources,
            userAgent, webView, attempt, hop, status, failure)) }
    }
}
