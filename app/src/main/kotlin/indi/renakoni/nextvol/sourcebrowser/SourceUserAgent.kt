package indi.renakoni.nextvol.sourcebrowser

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Keep Chromium's client hints consistent with the source's requested Chrome identity. */
internal fun WebSettings.applySourceUserAgent(value: String) {
    userAgentString = value
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
    val version = Regex("Chrome/(\\d+)(\\.[\\d.]+)?").find(value) ?: return
    val major = version.groupValues[1]
    val full = major + version.groupValues[2].ifEmpty { ".0.0.0" }
    try {
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
    } catch (_: RuntimeException) {
        // Some providers advertise the feature but reject metadata overrides.
        android.util.Log.w("SourceBrowser", "WebView rejected user agent metadata")
    }
}
