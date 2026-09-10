package hnovel.rhino

import cn.hutool.crypto.asymmetric.KeyType
import cn.hutool.crypto.asymmetric.RSA
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ScriptCryptoObjectsTest {
    private val frame = ScriptFrame("a", "legado")
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("Pure crypto must not use host authority") })
    private fun run(code: String): JsonElement {
        val result = engine.evaluate(code, frame)
        assertTrue(result.toString(), result is ScriptResult.Success)
        return Json.parseToJsonElement((result as ScriptResult.Success).json)
    }

    @Test fun symmetricFactoryUsesKnownVectorAndByteOrStringOverloads() {
        assertEquals(JsonPrimitive("29c3505f571420f6402299b31a02d73a"), run("""
            java.createSymmetricCrypto('AES/ECB/NoPadding','Thats my Kung Fu').encryptHex('Two One Nine Two')
        """))
        assertEquals(JsonPrimitive("chapter"), run("""
            var c=java.createSymmetricCrypto('AES/CBC/PKCS7Padding',java.strToBytes('0123456789abcdef'));
            c.setIv(java.strToBytes('abcdef0123456789'));
            c.decryptStr(c.encryptBase64('chapter'))
        """))
        assertEquals(JsonPrimitive("\u4e2d\u6587"), run("""
            var c=java.createSymmetricCrypto('AES/ECB/PKCS5Padding',null);
            c.decryptStr(c.encrypt('\u4e2d\u6587','UTF-16LE'),'UTF-16LE')
        """))
    }

    @Test fun rsaDefaultsToPublicAndPreservesPrivateDirectionAndLongBlocks() {
        val oracle = RSA()
        val text = "chapter".repeat(50)
        val encrypted = oracle.encryptBase64(text, KeyType.PrivateKey)
        assertEquals(JsonPrimitive(text), run("""
            var c=java.createAsymmetricCrypto('RSA');
            c.setPublicKey(java.base64DecodeToByteArray('${oracle.publicKeyBase64}'));
            c.setDecryptBlockSize(${(oracle.publicKey as java.security.interfaces.RSAKey).modulus.bitLength() / 8});
            c.decryptStr('$encrypted')
        """))
        val actual = run("""
            var c=java.createAsymmetricCrypto('RSA');
            c.setPublicKey(java.base64DecodeToByteArray('${oracle.publicKeyBase64}'));
            c.setEncryptBlockSize(${(oracle.publicKey as java.security.interfaces.RSAKey).modulus.bitLength() / 8 - 11});
            c.encryptBase64('$text')
        """).jsonPrimitive.content
        assertEquals(text, oracle.decryptStr(actual, KeyType.PrivateKey))
        assertEquals(JsonPrimitive("chapter"), run("""
            var c=java.createAsymmetricCrypto('RSA');
            c.decryptStr(c.encrypt('chapter'),null)
        """))
    }

    @Test fun signaturesMatchJceAndKeySettersReturnNativeFacade() {
        val oracle = cn.hutool.crypto.asymmetric.Sign("SHA256withRSA")
        val expected = Base64.getEncoder().encodeToString(oracle.sign("chapter"))
        val signed = run("""
            var s=java.createSign('SHA256withRSA');
            var same=s.setPrivateKey(java.base64DecodeToByteArray('${oracle.privateKeyBase64}'))===s;
            if(!same) throw Error('chaining');
            s.sign('chapter')
        """).jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()
        assertEquals(expected, Base64.getEncoder().encodeToString(signed))
        assertEquals(JsonPrimitive(true), run("""
            var s=java.createSign('SHA256withRSA');
            s.setPublicKey(java.base64DecodeToByteArray('${oracle.publicKeyBase64}'));
            s.verify(java.strToBytes('chapter'),java.base64DecodeToByteArray('$expected'))
        """))
    }

    @Test fun factoriesDoNotExposeNativeKeysClassesOrProvidersAndErrorsAreRedacted() {
        assertEquals(JsonArray(listOf(JsonPrimitive("undefined"), JsonPrimitive("undefined"), JsonPrimitive("invalid tool argument"))), run("""
            var c=java.createSymmetricCrypto('AES/ECB/PKCS5Padding','0123456789abcdef');
            var message='';try{c.decryptStr('wrong-secret')}catch(e){message=e.message}
            [typeof c.getClass,typeof c.getSecretKey,message]
        """))
    }

    @Test fun retainedCryptoUsesCurrentBudgetAndSafePrototypes() {
        ScriptLibrary("a", "legado", "var held={}; var Object=null; var Array=null;").use { library ->
            assertTrue(engine.evaluate("held.crypto=java.createSymmetricCrypto('AES/ECB/PKCS5Padding','0123456789abcdef');42", frame, library) is ScriptResult.Success)
            val small = RhinoScriptEngine(HostBridge { _, _ -> JsonNull }, ScriptLimits(maxBridgeChars=64))
            assertEquals(FailureCode.ResultTooLarge, (small.evaluate("held.crypto.encrypt.call(null,'a'.repeat(32))", frame, library) as ScriptResult.Failure).code)
            assertEquals(ScriptResult.Success("\"next\""), engine.evaluate("held.crypto.decryptStr(held.crypto.encrypt('next'))", frame, library))
        }
    }
}
