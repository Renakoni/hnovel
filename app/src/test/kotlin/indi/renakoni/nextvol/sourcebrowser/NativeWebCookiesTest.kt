package indi.renakoni.nextvol.sourcebrowser

import org.junit.Assert.*
import org.junit.Test

class NativeWebCookiesTest {
    private val url = "https://www.example.org/account/page"

    @Test fun explicitValuesPreserveBrowserAttributesAndNewCookiesStayHostOnly() {
        val snapshot = NativeCookieSnapshot(url, listOf(
            "sid=old; Domain=example.org; Path=/account; Secure; HttpOnly; SameSite=Strict; Expires=Wed, 21 Oct 2037 07:28:00 GMT",
            "host=old; Path=/; SameSite=Lax",
            "untouched=keep; Path=/; HttpOnly"
        ), true)
        val updates = nativeWebCookieUpdates(url, "sid=new=token; host=new; extra=one", snapshot)
        assertEquals(listOf(snapshot.cookies[0].replace("sid=old", "sid=new=token"),
            snapshot.cookies[1].replace("host=old", "host=new"), "extra=one; path=/"), updates)
        assertFalse(updates.last().contains("domain", true))
        assertFalse(updates.any { it.startsWith("untouched=") })
        assertTrue(nativeWebCookieUpdates(url, "sid=old", snapshot).isEmpty())
    }

    @Test fun incompleteMetadataCannotBeUsedToOverwriteBrowserAttributes() {
        val snapshot = NativeCookieSnapshot(url, listOf("sid=old; path=/; httponly"), false)
        assertTrue(nativeWebCookieUpdates(url, "sid=old", snapshot).isEmpty())
        assertEquals(listOf("new=value; path=/"), nativeWebCookieUpdates(url, "new=value", snapshot))
        assertThrows(IllegalStateException::class.java) { nativeWebCookieUpdates(url, "sid=changed", snapshot) }
    }

    @Test fun malformedOrOversizedHeadersFailBeforeProducingAnyUpdates() {
        val snapshot = NativeCookieSnapshot(url, emptyList(), true)
        for (header in listOf("good=one; broken", "bad name=value", "a=b\r\nInjected: bad", "a=" + "x".repeat(65536),
            (0..256).joinToString(";") { "k$it=value" })) {
            assertTrue(header.take(20), runCatching { nativeWebCookieUpdates(url, header, snapshot) }.isFailure)
        }
        assertTrue(nativeWebCookieUpdates(url, "", snapshot).isEmpty())
    }
}
