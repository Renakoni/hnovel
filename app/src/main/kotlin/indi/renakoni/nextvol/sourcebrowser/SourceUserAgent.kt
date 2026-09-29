package indi.renakoni.nextvol.sourcebrowser

import android.content.Context
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import hnovel.network.RequestEvidence
import hnovel.network.RequestObservation
import hnovel.network.UserAgentMetadataStatus
import hnovel.network.UserAgentSummary
import hnovel.network.WebViewUserAgentDiagnostic
import hnovel.network.WebViewEnvironmentDiagnostic
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keep Chromium's client hints consistent with the source's requested Chrome identity. */
internal fun WebSettings.applySourceUserAgent(value: String): UserAgentMetadataStatus {
    userAgentString = value
    return sourceUserAgentMetadataStatus(value, WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) { major, full ->
        val metadata = WebSettingsCompat.getUserAgentMetadata(this)
        val brands = metadata.brandVersionList.map { item ->
            if (item.brand !in setOf("Chromium", "Android WebView", "Google Chrome")) item
            else UserAgentMetadata.BrandVersion.Builder()
                .setBrand(if (item.brand == "Android WebView" && "; wv" !in value) "Google Chrome" else item.brand)
                .setMajorVersion(major).setFullVersion(full).build()
        }
        val updated = UserAgentMetadata.Builder(metadata).setBrandVersionList(brands).setFullVersion(full)
            .setMobile(" Mobile " in value)
        if ("Windows NT" in value) {
            updated.setPlatform("Windows").setPlatformVersion("10.0.0").setModel("")
                .setArchitecture("x86").setBitness(if ("Win64" in value) 64 else 32)
        }
        WebSettingsCompat.setUserAgentMetadata(this, updated.build())
    }.also { status ->
        if (status == UserAgentMetadataStatus.Rejected)
            android.util.Log.w("SourceBrowser", "WebView rejected user agent metadata")
    }
}

internal fun sourceUserAgentMetadataStatus(value: String, supported: Boolean, update: (String, String) -> Unit): UserAgentMetadataStatus {
    if (!supported) return UserAgentMetadataStatus.Unsupported
    val version = Regex("Chrome/(\\d+)(\\.[\\d.]+)?").find(value) ?: return UserAgentMetadataStatus.UnhandledUserAgent
    val major = version.groupValues[1]
    val full = major + version.groupValues[2].ifEmpty { ".0.0.0" }
    return try {
        update(major, full)
        UserAgentMetadataStatus.Applied
    } catch (_: RuntimeException) {
        UserAgentMetadataStatus.Rejected
    }
}

/** Trusted service-only callback. PageBridge's public whitelist deliberately excludes it. */
internal fun WebSettings.configureSourceUserAgent(job: BrowserJob, host: IBrowserHost, context: Context) {
    val requested = job.request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
    val status = requested?.let { applySourceUserAgent(it) } ?: UserAgentMetadataStatus.ProviderDefault
    if (job.observeUserAgent) reportSourceUserAgent(host, this, requested, status) {
        val provider = WebViewCompat.getCurrentWebViewPackage(context)
        WebViewEnvironmentDiagnostic(provider?.packageName, provider?.versionName,
            WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA),
            WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO))
    }
}

internal fun reportSourceUserAgent(host: IBrowserHost, settings: WebSettings, requested: String?, status: UserAgentMetadataStatus,
    environment: () -> WebViewEnvironmentDiagnostic? = { null }) {
    runCatching {
        val effective = settings.userAgentString
        val diagnostic = WebViewUserAgentDiagnostic(status, UserAgentSummary.from(effective), requested?.let { it == effective },
            runCatching(environment).getOrNull())
        BrowserWire.read(host.call("userAgentDiagnostic", Json.encodeToString(diagnostic)), 2048)
    }
}

internal fun RequestObservation?.recordWebViewUserAgent(arguments: String) {
    require(arguments.length <= 2048)
    val diagnostic = Json.decodeFromString<WebViewUserAgentDiagnostic>(arguments)
    diagnostic.userAgent.majorVersion?.let { require(it in 0..9999) }
    this?.record(RequestEvidence.WebViewSettings, webView = diagnostic)
}
