package indi.dmzz_yyhyy.lightnovelreader.data.web

import org.junit.Assert.*
import org.junit.Test

class BrowserRequestPolicyTest {
    @Test fun appliesHostAndSchemeBoundaryToEveryRequest() {
        val policy = BrowserRequestPolicy(setOf("example.test"))
        assertEquals(BrowserRequestDecision.Allow, policy.decide("https://example.test/page"))
        assertEquals(BrowserRequestDecision.Allow, policy.decide("http://example.test/script.js"))
        assertEquals(BrowserRequestDecision.Deny, policy.decide("https://cdn.example.test/script.js"))
        assertEquals(BrowserRequestDecision.Deny, policy.decide("wss://example.test/socket"))
        assertEquals(BrowserRequestDecision.Deny, policy.decide("file:///data/local/page.html"))
    }
}
