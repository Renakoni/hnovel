package indi.renakoni.nextvol.sourcebrowser

import hnovel.network.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SourceUserAgentTest {
    @Test fun failedSettingsReadbackOrHostCallbackDoesNotEscapeDiagnosticReporting() {
        val settings = mockk<android.webkit.WebSettings>()
        val host = mockk<IBrowserHost>()
        every { settings.userAgentString } returns "private-custom-agent"
        every { host.call(any(), any()) } throws IllegalStateException("dead diagnostic pipe")
        reportSourceUserAgent(host, settings, "private-custom-agent", UserAgentMetadataStatus.Unsupported)
        verify(exactly = 1) { host.call("userAgentDiagnostic", match { !it.contains("private-custom-agent") }) }
        every { settings.userAgentString } throws IllegalStateException("readback failed")
        reportSourceUserAgent(host, settings, null, UserAgentMetadataStatus.ProviderDefault)
    }

    @Test fun unsupportedProvidersDoNotAttemptMetadataUpdates() {
        assertEquals(UserAgentMetadataStatus.Unsupported, sourceUserAgentMetadataStatus(DESKTOP_USER_AGENT, false) { _, _ ->
            error("must not update")
        })
    }

    @Test fun nonChromeUaIsNotReportedAsApplied() {
        assertEquals(UserAgentMetadataStatus.UnhandledUserAgent, sourceUserAgentMetadataStatus("private-custom-agent", true) { _, _ ->
            error("must not update")
        })
    }

    @Test fun successfulSetterReportsAppliedAndPreservesTheExistingVersionDerivation() {
        var versions: Pair<String, String>? = null
        assertEquals(UserAgentMetadataStatus.Applied, sourceUserAgentMetadataStatus(DESKTOP_USER_AGENT, true) { major, full ->
            versions = major to full
        })
        assertEquals("130" to "130.0.0.0", versions)
        sourceUserAgentMetadataStatus("Chrome/133", true) { major, full -> versions = major to full }
        assertEquals("133" to "133.0.0.0", versions)
    }

    @Test fun providersRejectingAdvertisedSupportReturnAStatusInsteadOfThrowing() {
        assertEquals(UserAgentMetadataStatus.Rejected, sourceUserAgentMetadataStatus(DESKTOP_USER_AGENT, true) { _, _ ->
            throw UnsupportedOperationException("provider-private-message")
        })
    }

    @Test fun browserDiagnosticMessagesOnlyContainTypedSummaries() {
        val value = WebViewUserAgentDiagnostic(UserAgentMetadataStatus.Applied, UserAgentSummary.from(DESKTOP_USER_AGENT), true)
        val encoded = Json.encodeToString(value)
        assertEquals(value, Json.decodeFromString<WebViewUserAgentDiagnostic>(encoded))
        assertFalse(encoded.contains(DESKTOP_USER_AGENT))
        val observation: RequestObservation? = null
        observation.recordWebViewUserAgent(encoded)
        assertThrows(IllegalArgumentException::class.java) {
            observation.recordWebViewUserAgent(Json.encodeToString(value.copy(userAgent = value.userAgent.copy(majorVersion = 10000))))
        }
        assertThrows(IllegalArgumentException::class.java) { observation.recordWebViewUserAgent("x".repeat(2049)) }
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            observation.recordWebViewUserAgent(encoded.dropLast(1) + ",\"rawUa\":\"private\"}")
        }
    }

    @Test fun oldBrowserJobsDoNotEnableDiagnosticsAndNewJobsDoNotContainRequestIds() {
        val old = """{"request":{"id":"r","url":"https://example.org"},"options":{}}"""
        val job = Json.decodeFromString<BrowserJob>(old)
        assertFalse(job.observeUserAgent)
        val observed = job.copy(observeUserAgent = true)
        val encoded = Json.encodeToString(observed)
        assertEquals(observed, Json.decodeFromString<BrowserJob>(encoded))
        assertFalse(encoded.contains("requestId"))
    }
}
