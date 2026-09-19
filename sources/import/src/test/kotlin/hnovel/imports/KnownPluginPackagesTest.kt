package hnovel.imports

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

class KnownPluginPackagesTest {
    @Test fun maintainedDefinitionsUseExistingIdentityAndRevisionTransactions() {
        val store = SourceDefinitionStore(Files.createTempDirectory("known-package"))
        val importer = SourceDefinitionImporter(store)
        val packages = listOf(
            "7b482c76ad13d08ede1da549ffec93ff4dfda0e78e7577181e4462f668a8ec2e" to 1,
            "048bdc254856327dc95dc71777df95d28b1c1a55d35ef57889ccabb18eba2b1b" to 4)
        for ((hash, count) in packages) {
            val json = checkNotNull(KnownPluginPackages.definition(hash))
            val preview = importer.preview(json)
            assertEquals(emptyList<ImportIssue>(), preview.issues)
            assertEquals(count, preview.candidates.size)
            assertEquals(count, importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
                .items.count { it.outcome == ImportOutcome.Added })
            val repeated = importer.preview(json)
            assertEquals(count, importer.commit(repeated, repeated.candidates.map {
                ImportSelection(it.index, ImportDecision.Replace(checkNotNull(it.existing)))
            }).items.count { it.outcome == ImportOutcome.Unchanged })
        }
        assertEquals(5, store.list().map { it.sourceId }.distinct().size)
        assertFalse(store.list().single { it.importKey.contains("curiousers") }.enabled)
        assertNull(KnownPluginPackages.definition("0".repeat(64)))
    }

    @Test fun packageMagicOrNameNeverExecutesZipOrTreatsItAsJson() {
        val importer = SourceDefinitionImporter(SourceDefinitionStore(Files.createTempDirectory("unknown-package")))
        for ((bytes, name) in listOf(byteArrayOf(80,75,3,4,0) to "renamed.json", "{}".toByteArray() to "known.lnrp")) {
            assertEquals(ImportCode.PluginPackage, importer.previewStream(ByteArrayInputStream(bytes), name).issues.single().code)
        }
        var closed = false
        val oversized = object : InputStream() {
            var remaining = 16 * 1024 * 1024 + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                return minOf(len, remaining).also { b.fill(0, off, off + it); remaining -= it }
            }
            override fun close() { closed = true }
        }
        assertEquals(ImportCode.TooLarge, importer.previewStream(oversized, "large.lnrp").issues.single().code)
        assertFalse(closed) // ContentResolver retains ownership of its stream.
    }
}
