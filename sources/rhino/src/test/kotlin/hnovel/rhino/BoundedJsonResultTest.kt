package hnovel.rhino

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BoundedJsonResultTest {
    @Test fun stringChunksPreserveEveryUtf16CodeUnitAndCountEscapes() {
        val input = StringBuilder().apply { for (code in 0..65535) append(code.toChar()) }
        val encoded = BoundedJsonResult(256 * 1024).encode(input)
        assertEquals(input.toString(), Json.parseToJsonElement(encoded).jsonPrimitive.content)
        assertEquals(encoded, BoundedJsonResult(encoded.length).encode(input))
        assertThrows(ResultTooLarge::class.java) { BoundedJsonResult(encoded.length - 1).encode(input) }
    }
}
