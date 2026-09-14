package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.os.Bundle
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import org.junit.Test
import org.junit.runner.RunWith

/** Reports provider capabilities without changing its identity or any site's stored data. */
@RunWith(AndroidJUnit4::class)
class BrowserEnvironmentInstrumentedTest {
    @Test fun providerCapabilities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val values = linkedMapOf(
            "provider" to WebView.getCurrentWebViewPackage()?.versionName.orEmpty(),
            "multiProfile" to WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE).toString(),
            "cookieInfo" to WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO).toString(),
            "userAgentMetadata" to WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA).toString(),
            "requestedWithAllowList" to WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST).toString(),
            "directoryBasePaths" to WebViewFeature.isStartupFeatureSupported(context,
                WebViewFeature.STARTUP_FEATURE_SET_DIRECTORY_BASE_PATHS).toString(),
        )
        instrumentation.sendStatus(0, Bundle().apply { putString("browserEnvironment", values.toString()) })
    }
}
