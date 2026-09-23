package hnovel.network

import org.junit.Assert.*
import org.junit.Test

class SourcePermissionOriginTest {
    @Test fun completeAddressesHaveStableOrigins() {
        mapOf(
            " HTTPS://Books.Example/path?q=secret " to "https://books.example:443",
            "http://books.example:8080/" to "http://books.example:8080",
            "https://210.140.92.183/path" to "https://210.140.92.183:443",
            "https://[2001:db8::1]:8443/path" to "https://[2001:db8::1]:8443",
            "https://bücher.example/" to "https://xn--bcher-kva.example:443"
        ).forEach { (input, expected) ->
            assertEquals(input, expected, sourcePermissionOrigin(input))
            assertEquals(expected, sourcePermissionOrigin(expected))
        }
    }

    @Test fun prefixesTemplatesCredentialsAndMalformedAuthoritiesAreRejected() {
        listOf("https://210.140", "https://999.140.92.183", "https://{{host}}/", "https://\${host}/",
            "https://books.example.*", "https://user:password@books.example/", "https://@books.example/",
            "https://books.example:65536", "https://books.example:-1", "https://", "books.example", "file:///tmp",
            "https://books.example\\other", "https://two hosts.example/").forEach {
            assertNull(it, sourcePermissionOrigin(it))
        }
    }
}
