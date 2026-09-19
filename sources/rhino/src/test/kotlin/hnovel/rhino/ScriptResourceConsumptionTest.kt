package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptResourceConsumptionTest {
    @Test fun escapedOutputLimitIsCheckedBeforeDeletingExtractedText() {
        val bytes = "\uD83D\uDE00".repeat(20).toByteArray(Charsets.UTF_8)
        val files = buildJsonObject { put("a.txt",java.util.Base64.getEncoder().encodeToString(bytes)) }
        assertTrue(files.toString().length < 128)
        var deleted = false
        val engine = RhinoScriptEngine(HostBridge { name, _ -> when (name) {
            "resource.readArchive" -> files
            "java.deleteFile" -> { deleted=true; JsonPrimitive(true) }
            else -> error("Unexpected bridge")
        } },ScriptLimits(maxBridgeChars=128))
        val result = engine.evaluate("java.getTxtInFolder('directory')",ScriptFrame("a","legado"))
        assertEquals(FailureCode.ResultTooLarge,(result as ScriptResult.Failure).code)
        assertFalse(deleted)
    }
}
