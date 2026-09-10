package hnovel.rhino

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveDecoderTest {
    private fun zip(path: String, data: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry(path)); it.write(data); it.closeEntry() }
    }.toByteArray()
    @Test fun archivesRejectTraversalAndInflatedSizeBeforePublication() {
        val valid = zip("dir/chapter.txt", "chapter".toByteArray())
        assertEquals("chapter", ArchiveDecoder.Zip.decode(valid, 1024).getValue("dir/chapter.txt").toString(Charsets.UTF_8))
        for (path in listOf("../chapter", "/chapter", "dir/../../chapter", "C:/chapter", "dir\\chapter")) {
            assertTrue(runCatching { ArchiveDecoder.Zip.decode(zip(path, byteArrayOf(1)), 1024) }.isFailure)
        }
        assertTrue(runCatching { ArchiveDecoder.Zip.decode(zip("bomb", ByteArray(10000)), 256) }.exceptionOrNull() is ArchiveSizeLimitExceeded)
    }
    @Test fun archiveInputAndAggregateOutputUseTheSizeFailureContract() {
        val exact = zip("chapter", ByteArray(1024))
        assertEquals(1024, ArchiveDecoder.Zip.decode(exact, 1024).getValue("chapter").size)
        assertTrue(runCatching { ArchiveDecoder.Zip.decode(exact, exact.size - 1) }.exceptionOrNull() is ArchiveSizeLimitExceeded)
        val aggregate = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { archive ->
            for (name in listOf("one", "two")) {
                archive.putNextEntry(ZipEntry(name)); archive.write(ByteArray(600)); archive.closeEntry()
            }
        } }.toByteArray()
        assertTrue(runCatching { ArchiveDecoder.Zip.decode(aggregate, 1024) }.exceptionOrNull() is ArchiveSizeLimitExceeded)
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") }, ScriptLimits(maxBridgeChars = 1024))
        val hex = aggregate.joinToString("") { "%02x".format(it) }
        assertEquals(FailureCode.ResultTooLarge, (engine.evaluate("java.getZipStringContent('$hex','one')", ScriptFrame("a", "legado")) as ScriptResult.Failure).code)
        assertEquals(ScriptResult.Success("42"), engine.evaluate("21*2", ScriptFrame("a", "legado")))
    }
    @Test fun zipContentOverloadsReturnBytesNullAndDecodedText() {
        val hex = zip("chapter", "chapter".toByteArray()).joinToString("") { "%02x".format(it) }
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
        val result = engine.evaluate("[java.getZipStringContent('$hex','chapter'),java.getZipByteArrayContent('$hex','missing'),java.getZipStringContent('$hex','missing')]", ScriptFrame("a", "legado"))
        assertEquals(ScriptResult.Success("[\"chapter\",null,\"\"]"), result)
    }
}
