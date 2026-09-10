package hnovel.execution

import kotlinx.serialization.json.*

/** Checks untrusted worker payloads before the recursive JSON parser runs on a host Binder thread. */
object BridgeWire {
    const val MAX_BYTES = 256 * 1024

    fun arguments(bytes: ByteArray): List<JsonElement> = Json.parseToJsonElement(validate(bytes)).jsonArray

    /** Shared preflight for every untrusted worker message, including execution results. */
    fun validate(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "Bridge request too large" }
        val text = bytes.toString(Charsets.UTF_8)
        var quoted = false
        var escaped = false
        var depth = 0
        for (char in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '[', '{' -> { depth++; require(depth <= 64) { "Bridge nesting too deep" } }
                ']', '}' -> { depth--; require(depth >= 0) { "Invalid bridge JSON" } }
            }
        }
        require(!quoted && depth == 0) { "Invalid bridge JSON" }
        return text
    }
}
