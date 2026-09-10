package hnovel.rhino

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** A worker-only decoder. Android supplies the pinned libarchive implementation for RAR/7z too. */
fun interface ArchiveDecoder {
    fun decode(bytes: ByteArray, maxBytes: Int): Map<String, ByteArray>

    companion object {
        fun validatePath(path: String) {
            require(path.length in 1..1024 && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path)
            require(path.split('/').none { it.isEmpty() || it == "." || it == ".." })
        }

        val Zip = ArchiveDecoder { bytes, maxBytes ->
            val files = linkedMapOf<String, ByteArray>()
            var total = 0L
            var entries = 0
            ZipInputStream(bytes.inputStream()).use { input ->
                require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte())
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(++entries <= 256)
                    validatePath(entry.name.removeSuffix("/"))
                    if (entry.isDirectory) continue
                    require(!files.containsKey(entry.name))
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > maxBytes) throw ResultTooLarge()
                        output.write(buffer, 0, size)
                    }
                    files[entry.name] = output.toByteArray()
                }
            }
            files
        }
    }
}
