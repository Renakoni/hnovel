package hnovel.execution

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Large novel pages and catalogues share the existing bounded Binder messages. */
object ExecutionPayload {
    fun pack(bytes: ByteArray, maxPacketBytes: Int): ByteArray? {
        val packed = if (bytes.size <= 64 * 1024) bytes else ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
        }.toByteArray().let { if (it.size < bytes.size) it else bytes }
        return packed.takeIf { it.size <= maxPacketBytes }
    }

    fun unpack(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) {
            require(bytes.size <= maxBytes)
            return bytes
        }
        val output = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= maxBytes) { "Execution payload exceeds limit" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }
}
