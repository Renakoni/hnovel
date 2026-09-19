package hnovel.rhino

import cn.hutool.core.codec.Base64 as ReferenceBase64
import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.digest.HMac
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptToolsTest {
    private val frame = ScriptFrame("source-a", "legado")
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("Pure tools must not call the host") })
    private fun run(script: String): JsonElement = Json.parseToJsonElement((engine.evaluate(script, frame) as ScriptResult.Success).json)

    @Test fun hashesMatchFixedVectorsAndThePinnedReferenceDependency() {
        assertEquals(JsonPrimitive("900150983cd24fb0d6963f7d28e17f72"), run("java.md5Encode('abc')"))
        assertEquals(JsonPrimitive("3cd24fb0d6963f7d"), run("java.md5Encode16('abc')"))
        assertEquals(JsonPrimitive("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), run("java.digestHex('abc','SHA-256')"))
        assertEquals(JsonPrimitive("qZk+NkcGgWq6PiVxeFDCbJzQ2J0="), run("java.digestBase64Str('abc','SHA-1')"))
        for (text in listOf("", "abc", "小说目录")) {
            val input = JsonPrimitive(text).toString()
            assertEquals(JsonPrimitive(DigestUtil.md5Hex(text)), run("java.md5Encode($input)"))
            assertEquals(JsonPrimitive(DigestUtil.sha256Hex(text)), run("java.digestHex($input,'SHA-256')"))
        }
    }

    @Test fun hmacUsesUtf8DataAndKeyWithReferenceOutputs() {
        val data = "The quick brown fox jumps over the lazy dog"
        assertEquals(JsonPrimitive("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"),
            run("java.HMacHex('$data','HmacSHA256','key')"))
        val reference = HMac("HmacSHA256", "key".toByteArray()).digest(data)
        assertEquals(JsonPrimitive(ReferenceBase64.encode(reference)), run("java.HMacBase64('$data','HmacSHA256','key')"))
    }

    @Test fun byteArraysAreSignedAndCharsetRoundTripsRemainNativeJs() {
        assertEquals(Json.parseToJsonElement("[-28,-72,-83]"), run("java.strToBytes('中')"))
        assertEquals(Json.parseToJsonElement("[-42,-48]"), run("java.strToBytes('中','GBK')"))
        assertEquals(JsonPrimitive("中"), run("java.bytesToStr([214,208],'GBK')"))
        assertEquals(JsonPrimitive("小说"), run("java.bytesToStr(java.strToBytes('小说'))"))
        assertEquals(JsonPrimitive("undefined"), run("typeof java.strToBytes('x').getClass"))
    }

    @Test fun hexAndBase64MatchHutoolIncludingOddHexAndUnicode() {
        for (text in listOf("", "abc", "中", "a\n'b")) {
            val literal = JsonPrimitive(text).toString()
            assertEquals(JsonPrimitive(HexUtil.encodeHexStr(text)), run("java.hexEncodeToString($literal)"))
            assertEquals(JsonPrimitive(ReferenceBase64.encode(text)), run("java.base64Encode($literal)"))
            assertEquals(JsonPrimitive(text), run("java.base64Decode(java.base64Encode($literal))"))
        }
        for (encoded in listOf("f", "abc", "FF00ab", "", " \t", " a b\nc ", "\uFEFFf\u202A\u0000\u3164\u2800\u180E")) {
            val literal = JsonPrimitive(encoded).toString()
            val bytes = HexUtil.decodeHex(encoded)
            assertEquals(bytes?.let { JsonArray(it.map { byte -> JsonPrimitive(byte.toInt()) }) } ?: JsonNull,
                run("java.hexDecodeToByteArray($literal)"))
            assertEquals(JsonPrimitive(HexUtil.decodeHexStr(encoded)), run("java.hexDecodeToString($literal)"))
        }
        assertEquals(JsonPrimitive("中"), run("java.base64Decode('1tA=','GBK')"))
        assertEquals(JsonNull, run("java.base64DecodeToByteArray(null)"))
    }

    @Test fun stringBase64OverloadsPreserveReferenceNullAndPermissiveDecoding() {
        for (encoded in listOf(null, "", " ", "a", "YQ", "YQ==", "Y=Q", "YQ==YQ==", "77-_", "Y!Q\n==")) {
            val literal = JsonPrimitive(encoded).toString()
            assertEquals(JsonPrimitive(ReferenceBase64.decodeStr(encoded)), run("java.base64Decode($literal)"))
            assertEquals(JsonPrimitive(ReferenceBase64.decodeStr(encoded, Charsets.ISO_8859_1)),
                run("java.base64Decode($literal,'ISO-8859-1')"))
        }
    }

    @Test fun base64FlagsPreservePaddingWrappingAndUrlAlphabet() {
        assertEquals(JsonPrimitive("YQ==\n"), run("java.base64Encode('a',0)"))
        assertEquals(JsonPrimitive("YQ==\r\n"), run("java.base64Encode('a',4)"))
        assertEquals(JsonPrimitive("YQ"), run("java.base64Encode('a',3)"))
        assertEquals(JsonPrimitive("77-_"), run("java.base64Encode('\uffff',10)"))
        assertEquals(JsonPrimitive("\uffff"), run("java.base64Decode('77-_',8)"))
        assertEquals(Json.parseToJsonElement("[97]"), run("java.base64DecodeToByteArray(' YQ==\n',0)".replace("\n", "\\n")))
        val wrapped = run("java.base64Encode('a'.repeat(60),0)").jsonPrimitive.content
        assertEquals(listOf(76, 4, 0), wrapped.split('\n').map { it.length })
    }

    @Test fun urlAndTimeFormattingFollowTheReferenceContracts() {
        assertEquals(JsonPrimitive("a+b%2F%E4%B8%AD"), run("java.encodeURI('a b/中')"))
        assertEquals(JsonPrimitive("%D6%D0"), run("java.encodeURI('中','GBK')"))
        assertEquals(JsonPrimitive(""), run("java.encodeURI('secret','unsupported-charset')"))
        assertEquals(JsonPrimitive("1970-01-01 01:00"), run("java.timeFormatUTC(0,'yyyy-MM-dd HH:mm',3600000)"))
    }

    @Test fun invalidToolsAreCatchableAndBoundedWithoutLeakingArguments() {
        assertEquals(JsonPrimitive("invalid tool argument"), run("try{java.digestHex('secret','bad-algorithm')}catch(e){e.message}"))
        val small = RhinoScriptEngine(HostBridge { _, _ -> error("No host access") }, ScriptLimits(maxBridgeChars = 128))
        assertEquals(FailureCode.ResultTooLarge, (small.evaluate("java.base64Encode('a'.repeat(100))", frame) as ScriptResult.Failure).code)
        assertEquals("\"YQ==\"", (small.evaluate("java.base64Encode('a')", frame) as ScriptResult.Success).json)
    }
}
