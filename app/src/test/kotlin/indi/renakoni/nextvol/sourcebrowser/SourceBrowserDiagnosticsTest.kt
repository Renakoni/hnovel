package indi.renakoni.nextvol.sourcebrowser

import hnovel.network.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceBrowserDiagnosticsTest {
    private val request = BrokerRequest("r", "https://example.org")
    private val website = BrowserOptions(nativeWebsite = true)

    @Test fun explicitRenderingAndSyntheticDocumentsExplainTheirMediatedRoute() {
        assertEquals(RequestReason.BrowserRendering, mediatedBrowserReason(request, BrowserOptions(), true))
        assertEquals(RequestReason.SuppliedHtml, mediatedBrowserReason(request, website.copy(html = "private"), true))
        assertEquals(RequestReason.VerificationDocument, mediatedBrowserReason(request, website.copy(verificationCode = true), true))
    }

    @Test fun nativeWebsiteFallbackReportsTheFirstUnmetExistingRequirement() {
        assertEquals(RequestReason.NativeUnavailable, mediatedBrowserReason(request, website, false))
        assertEquals(RequestReason.HttpMethod, mediatedBrowserReason(request.copy(method = "POST"), website, true))
        assertEquals(RequestReason.RedirectsDisabled, mediatedBrowserReason(request.copy(followRedirects = false), website, true))
        assertEquals(RequestReason.HexResponse, mediatedBrowserReason(request.copy(responseAsHex = true), website, true))
        assertEquals(RequestReason.CacheOnly, mediatedBrowserReason(request.copy(cache = CacheMode.Only), website, true))
        assertEquals(RequestReason.ExplicitCookie, mediatedBrowserReason(request.copy(headers = mapOf("cOoKiE" to "secret")), website, true))
    }
}
