package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import hnovel.rhino.ArchiveDecoder
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Native parsing is confined to the isolated worker and its hard process deadline. */
internal object AndroidArchiveDecoder : ArchiveDecoder {
    override fun decode(bytes: ByteArray, maxBytes: Int): Map<String, ByteArray> {
        require(bytes.size <= maxBytes)
        val archive = Archive.readNew()
        try {
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            val source = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            Archive.readOpenMemory(archive, source)
            val files = linkedMapOf<String, ByteArray>()
            val buffer = ByteBuffer.allocateDirect(8192)
            val chunk = ByteArray(8192)
            var total = 0L
            var entries = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                require(++entries <= 256)
                val path = requireNotNull(ArchiveEntry.pathnameUtf8(entry)).removeSuffix("/")
                ArchiveDecoder.validatePath(path)
                require(!ArchiveEntry.isEncrypted(entry) && ArchiveEntry.symlink(entry) == null && ArchiveEntry.hardlink(entry) == null)
                if (ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR) continue
                require(ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG && path !in files)
                val output = ByteArrayOutputStream()
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    buffer.clear()
                    Archive.readData(archive, buffer)
                    val count = buffer.position()
                    if (count == 0) break
                    total += count
                    require(total <= maxBytes)
                    buffer.flip(); buffer.get(chunk, 0, count)
                    output.write(chunk, 0, count)
                }
                files[path] = output.toByteArray()
            }
            return files
        } finally { Archive.free(archive) }
    }
}
