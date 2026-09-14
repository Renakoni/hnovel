package hnovel.execution

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BridgeReplyTest {
    @Test fun repliesCanExceedBinderButRetainSizeAndDepthBounds() {
        val text = "\"" + "x".repeat(700000) + "\""
        assertEquals(text, BridgeWire.readReply(ByteArrayInputStream(text.toByteArray())))
        assertThrows(IllegalArgumentException::class.java) {
            BridgeWire.readReply(ByteArrayInputStream(("[".repeat(65) + "]".repeat(65)).toByteArray()))
        }
        val oversized = object : InputStream() {
            var remaining = BridgeWire.MAX_REPLY_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 32 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length, remaining)
                buffer.fill(32, offset, offset + count); remaining -= count
                return count
            }
        }
        assertThrows(IllegalArgumentException::class.java) { BridgeWire.readReply(oversized) }
    }
}
