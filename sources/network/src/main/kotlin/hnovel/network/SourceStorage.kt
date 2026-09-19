package hnovel.network

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Opaque keys become bounded filenames. No caller-provided path or directory is accepted. */
internal class SourceStorage(root: Path, namespace: List<String>, private val limits: BrokerLimits,
    private val cipher: StorageCipher = StorageCipher.Plain) {
    private val identity = namespace.joinToString("") { "${it.length}:$it" }
    private val directory: Path
    init {
        Files.createDirectories(root)
        val realRoot = root.toRealPath()
        directory = realRoot.resolve(hash(namespace.joinToString("") { "${it.length}:$it" }))
        Files.createDirectories(directory)
        check(!Files.isSymbolicLink(directory) && directory.toRealPath().parent == realRoot)
    }

    @Synchronized fun read(key: String): StorageResult = operation {
        val path = path(key)
        if (!Files.exists(path, NOFOLLOW_LINKS)) StorageResult.Value(null)
        else {
            if (Files.size(path) > limits.maxStorageBytes) return@operation StorageResult.Failure(FailureCode.StorageQuota)
            StorageResult.Value(cipher.open(Files.readAllBytes(path), identity + hash(key)).toString(Charsets.UTF_8))
        }
    }

    @Synchronized fun write(key: String, value: String?): StorageResult = operation {
        val path = path(key)
        if (value == null) { Files.deleteIfExists(path); return@operation StorageResult.Value(null) }
        val bytes = cipher.seal(value.toByteArray(Charsets.UTF_8), identity + hash(key))
        val files = Files.list(directory).use { it.iterator().asSequence().toList() }
        if (files.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) }) return@operation StorageResult.Failure(FailureCode.StorageUnavailable)
        val oldSize = if (Files.exists(path)) Files.size(path) else 0L
        val total = files.sumOf { Files.size(it) } - oldSize + bytes.size
        if (total > limits.maxStorageBytes || (path !in files && files.size >= limits.maxStorageEntries)) {
            return@operation StorageResult.Failure(FailureCode.StorageQuota)
        }
        val temp = Files.createTempFile(directory, "write-", ".tmp")
        try {
            Files.write(temp, bytes)
            try { Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
        StorageResult.Value(value)
    }

    @Synchronized fun clear(): StorageResult = operation {
        check(!Files.isSymbolicLink(directory) && directory.toRealPath() == directory)
        Files.list(directory).use { files -> files.forEach { file ->
            check(Files.isRegularFile(file, NOFOLLOW_LINKS))
            Files.delete(file)
        } }
        StorageResult.Value(null)
    }

    private fun path(key: String): Path {
        require(key.length <= 65536)
        check(!Files.isSymbolicLink(directory) && directory.toRealPath() == directory)
        val path = directory.resolve(hash(key))
        check(!Files.isSymbolicLink(path))
        if (Files.exists(path, NOFOLLOW_LINKS)) check(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath().parent == directory)
        return path
    }

    private inline fun operation(block: () -> StorageResult): StorageResult = try { block() }
        catch (_: Exception) { StorageResult.Failure(FailureCode.StorageUnavailable) }
}

internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
