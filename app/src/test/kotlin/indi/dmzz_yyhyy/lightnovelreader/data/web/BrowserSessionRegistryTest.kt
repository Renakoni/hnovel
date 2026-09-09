package indi.dmzz_yyhyy.lightnovelreader.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.*
import org.junit.Test

class BrowserSessionRegistryTest {
    @Test fun profilesAreStableAndDifferentSourcesCannotShareCallbacks() {
        val registry = BrowserSessionRegistry()
        val a = registry.open(Identifier("x", "a"))
        val b = registry.open(Identifier("x", "b"))
        assertNotEquals(a.profileName, b.profileName)
        assertEquals(a.profileName, registry.open(Identifier("x", "a")).profileName)
        assertEquals(BrowserCallbackResult.Stale, registry.callback(a))
        assertEquals(BrowserCallbackResult.Accepted, registry.callback(b))
    }

    @Test fun cancellationInvalidatesLateCallbacks() {
        val registry = BrowserSessionRegistry()
        val session = registry.open(Identifier("x", "a"))
        registry.cancel(session)
        assertEquals(BrowserCallbackResult.Stale, registry.callback(session))
        assertEquals(BrowserCallbackResult.Cancelled, registry.callback(session, cancelled = true))
    }
}
