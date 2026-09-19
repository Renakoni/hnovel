package hnovel.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalStorageRetentionTest {
    private fun parse(text: String) = LocalStorageRetention.parse(Json.parseToJsonElement(text))

    @Test fun declarationsNormalizeExactOriginsAndNeverGrantAccess() {
        val declaration = parse("""{"https://EXAMPLE.org/":["theme","__proto__"],"https://other.org":["size"]}""")
        val approved = declaration.approved(listOf(NetworkGrant("https://example.org")))
        assertEquals(mapOf("https://example.org:443" to listOf("theme", "__proto__")), approved.origins)
        assertTrue(LocalStorageRetention.parse(null).origins.isEmpty())
        assertEquals(mapOf("https://example.org:443" to mapOf("theme" to "dark")), approved.select(mapOf(
            "https://example.org:443" to mapOf("theme" to "dark", "payload" to "old-account"),
            "https://other.org:443" to mapOf("size" to "large"))))
    }

    @Test fun malformedDeclarationsAndDuplicateCanonicalOriginsAreRejected() {
        for (text in listOf("[]", "true", "{\"file:///tmp\":[\"theme\"]}", "{\"https://*.example.org\":[]}",
            "{\"https://example.org/path\":[\"theme\"]}", "{\"https://example.org?query\":[]}",
            "{\"https://example.org#fragment\":[]}", "{\"https://user:pass@example.org\":[]}",
            "{\"https://example.org\":[1]}", "{\"https://example.org\":[\"\"]}",
            "{\"https://example.org\":[\"theme\",\"theme\"]}",
            "{\"https://example.org\":[],\"https://example.org:443/\":[]}")) {
            assertThrows(text, IllegalArgumentException::class.java) { parse(text) }
        }
    }

    @Test fun snapshotsRejectUndeclaredKeysAndBoundValuesBeforeCrossProcessTransfer() {
        val origin = "https://example.org:443"
        val declaration = LocalStorageRetention(mapOf(origin to listOf("theme", "a", "b", "c", "d")))
        assertThrows(IllegalArgumentException::class.java) { declaration.validate(mapOf(origin to mapOf("payload" to "account"))) }
        assertThrows(IllegalArgumentException::class.java) { declaration.validate(mapOf(origin to mapOf("theme" to "x".repeat(16385)))) }
        assertThrows(IllegalArgumentException::class.java) {
            declaration.validate(mapOf(origin to listOf("a", "b", "c", "d").associateWith { "x".repeat(16384) }))
        }
        assertThrows(IllegalArgumentException::class.java) { LocalStorageRetention(mapOf(origin to (1..65).map(Int::toString))) }
        assertThrows(IllegalArgumentException::class.java) { LocalStorageRetention(mapOf(origin to listOf("x".repeat(129)))) }
        assertThrows(IllegalArgumentException::class.java) { LocalStorageRetention((1..9).associate { "https://s$it.org:443" to listOf("x") }) }
    }
}
