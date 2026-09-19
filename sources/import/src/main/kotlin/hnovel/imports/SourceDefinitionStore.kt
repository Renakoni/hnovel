package hnovel.imports

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.ConcurrentHashMap

/** One durable snapshot is the commit point. The directory is chosen by the trusted host. */
class SourceDefinitionStore(directory: Path, private val limits: ImportLimits = ImportLimits()) {
    private val root: Path
    init {
        Files.createDirectories(directory)
        root = directory.toRealPath()
    }

    fun list(): List<SourceDefinition> = locked { read().sources.toList() }

    internal fun <T> transaction(change: (MutableList<SourceDefinition>) -> T): T = locked {
        val before = read()
        val next = before.sources.toMutableList()
        val result = change(next)
        if (next != before.sources) write(Snapshot(sources = next))
        result
    }

    private fun read(): Snapshot {
        val file = path("definitions.json")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return Snapshot()
        if (Files.size(file) > limits.maxStoredBytes) throw ImportFailure(ImportCode.StorageQuota)
        val snapshot = Json.decodeFromString<Snapshot>(Files.readAllBytes(file).toString(Charsets.UTF_8))
        check(snapshot.version == 1)
        check(snapshot.sources.size <= limits.maxStoredEntries)
        check(snapshot.sources.map { it.sourceId }.toSet().size == snapshot.sources.size)
        check(snapshot.sources.map { it.profile to it.importKey }.toSet().size == snapshot.sources.size)
        snapshot.sources.forEach { source ->
            check(source.sourceId.matches(Regex("[a-f0-9]{64}")) && source.revision > 0)
            check(source.contentDigest == digest(source.rawJson))
        }
        return snapshot
    }

    private fun write(snapshot: Snapshot) {
        val bytes = Json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        if (bytes.size > limits.maxStoredBytes || snapshot.sources.size > limits.maxStoredEntries) throw ImportFailure(ImportCode.StorageQuota)
        val destination = path("definitions.json")
        val temporary = Files.createTempFile(root, "pending-", ".json")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // No non-atomic fallback: if unsupported, leave the previous snapshot intact.
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun path(name: String): Path {
        check(!Files.isSymbolicLink(root) && root.toRealPath() == root)
        return root.resolve(name).also { path ->
            check(!Files.isSymbolicLink(path))
            if (Files.exists(path, NOFOLLOW_LINKS)) check(Files.isRegularFile(path, NOFOLLOW_LINKS))
        }
    }

    private fun <T> locked(block: () -> T): T = synchronized(locks.computeIfAbsent(root) { Any() }) {
        try {
            FileChannel.open(path("definitions.lock"), CREATE, WRITE).use { channel ->
                channel.lock().use { block() }
            }
        } catch (failure: ImportFailure) { throw failure }
          catch (_: Exception) { throw ImportFailure(ImportCode.StorageUnavailable) }
    }

    @Serializable private data class Snapshot(val version: Int = 1, val sources: List<SourceDefinition> = emptyList())
    companion object { private val locks = ConcurrentHashMap<Path, Any>() }
}
