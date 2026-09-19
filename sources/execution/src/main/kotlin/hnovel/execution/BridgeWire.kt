package hnovel.execution

import kotlinx.serialization.json.*

/** Checks untrusted worker payloads before the recursive JSON parser runs on a host Binder thread. */
object BridgeWire {
    const val MAX_BYTES = 256 * 1024
    const val MAX_REPLY_BYTES = 16 * 1024 * 1024

    /** Host replies travel through a pipe; bound raw bytes before parsing their JSON. */
    fun readReply(input: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_REPLY_BYTES) { "Bridge response too large" }
            output.write(buffer, 0, count)
        }
        return validate(output.toByteArray(), MAX_REPLY_BYTES)
    }

    fun arguments(bytes: ByteArray): List<JsonElement> = Json.parseToJsonElement(validate(bytes)).jsonArray

    /** Shared preflight for every untrusted worker message, including execution results. */
    fun validate(bytes: ByteArray): String = validate(bytes, MAX_BYTES)

    fun validate(bytes: ByteArray, maxBytes: Int): String {
        require(bytes.size <= maxBytes) { "Bridge request too large" }
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
