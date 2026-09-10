package indi.dmzz_yyhyy.lightnovelreader.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.*
import org.junit.Test

class SourceSessionManagerTest {
    private val source = Identifier("test", "source-a")

    @Test fun sessionsAreSourceScopedAndLogoutInvalidatesLateResults() {
        val manager = SourceSessionManager()
        val a = manager.begin(source)
        val b = manager.begin(Identifier("test", "source-b"))
        assertTrue(manager.setCookies(a, mapOf("sid" to "a")))
        assertEquals(mapOf("sid" to "a"), manager.current(source).cookies)
        assertTrue(manager.accepts(a))
        val next = manager.logout(a)
        assertFalse(manager.accepts(a))
        assertFalse(manager.setCookies(a, mapOf("sid" to "late")))
        assertTrue(manager.accepts(next))
        assertTrue(manager.accepts(b))
        assertNotEquals(a.generation, next.generation)
        assertTrue(next.cookies.isEmpty())
        val invalidLogout = manager.logout(a)
        assertFalse(invalidLogout.active)
        assertTrue(invalidLogout.cookies.isEmpty())
        assertFalse(manager.accepts(invalidLogout))
    }

    @Test fun missingSourceStartsInactive() {
        val session = SourceSessionManager().current(source)
        assertFalse(session.active)
        assertTrue(session.cookies.isEmpty())
    }

    @Test fun logsRedactCredentialLikeQueryValues() {
        val redacted = SourceSessionManager().redact("https://x.test/login?token=secret&ok=yes password=hunter2")
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("hunter2"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test fun sessionsFromAnotherManagerCannotWriteCookies() {
        val first = SourceSessionManager()
        val second = SourceSessionManager()
        val foreign = second.begin(source)
        assertFalse(first.setCookies(foreign, mapOf("sid" to "forged")))
        assertTrue(first.current(source).cookies.isEmpty())
    }
}
