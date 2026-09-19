package hnovel.imports

import java.io.InputStream
import java.security.MessageDigest

/** Exact, reviewed release files are selectors for maintained JSON, never executable packages. */
internal object KnownPluginPackages {
    private const val MAX_PACKAGE_BYTES = 16 * 1024 * 1024
    private val definitions = mapOf(
        "7b482c76ad13d08ede1da549ffec93ff4dfda0e78e7577181e4462f668a8ec2e" to "potato-1.0-1.json",
        "048bdc254856327dc95dc71777df95d28b1c1a55d35ef57889ccabb18eba2b1b" to "hoohoo-1.0.3-4.json"
    )

    fun read(input: InputStream): String? {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_PACKAGE_BYTES - size + 1))
            if (count < 0) break
            size += count
            if (size > MAX_PACKAGE_BYTES) throw ImportFailure(ImportCode.TooLarge)
            hash.update(buffer, 0, count)
        }
        return definition(hash.digest().joinToString("") { "%02x".format(it) })
    }

    internal fun definition(sha256: String): String? = definitions[sha256]?.let { name ->
        checkNotNull(javaClass.getResourceAsStream("/known-sources/$name")) { "Missing maintained source definition" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
