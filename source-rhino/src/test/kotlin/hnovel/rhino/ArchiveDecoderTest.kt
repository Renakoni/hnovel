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
        assertTrue(runCatching { ArchiveDecoder.Zip.decode(zip("bomb", ByteArray(10000)), 256) }.exceptionOrNull() is ResultTooLarge)
    }
    @Test fun zipContentOverloadsReturnBytesNullAndDecodedText() {
        val hex = zip("chapter", "chapter".toByteArray()).joinToString("") { "%02x".format(it) }
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
        val result = engine.evaluate("[java.getZipStringContent('$hex','chapter'),java.getZipByteArrayContent('$hex','missing'),java.getZipStringContent('$hex','missing')]", ScriptFrame("a", "legado"))
        assertEquals(ScriptResult.Success("[\"chapter\",null,\"\"]"), result)
    }
}
