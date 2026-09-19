package hnovel.rhino

import cn.hutool.crypto.KeyUtil
import cn.hutool.crypto.asymmetric.AsymmetricCrypto
import cn.hutool.crypto.asymmetric.KeyType
import cn.hutool.crypto.asymmetric.Sign
import cn.hutool.crypto.symmetric.SymmetricCrypto
import kotlinx.serialization.json.*
import org.mozilla.javascript.*
import java.nio.charset.Charset
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

/** Pinned crypto library behind explicit native-JS methods; no JCE/Hutool object crosses into JS. */
internal object ScriptCryptoObjects {
    val factories = setOf("createSymmetricCrypto", "createAsymmetricCrypto", "createSign")

    fun create(cx: Context, scope: Scriptable, name: String, values: List<JsonElement>): ScriptableObject {
        val args = ScriptTools.Arguments(values)
        args.count(if (name == "createSymmetricCrypto") 2 else 1, if (name == "createSymmetricCrypto") 3 else 1)
        val algorithm = args.text(0)
        val realm = ScriptRealm.current(cx)
        val target = realm.objectIn(scope)
        fun method(name: String, action: (List<JsonElement>) -> JsonElement?) {
            target.defineProperty(name, realm.method(scope) { context, active, raw ->
                // Saved methods use the current invocation's budget, including call/apply/bind.
                val limit = context.getThreadLocal(bridgeLimitKey) as Int
                val data = BoundedJsonResult(limit).encode(ScriptRealm.current(context).arrayIn(active, raw.copyOf()))
                val result = try { action(Json.parseToJsonElement(data).jsonArray) }
                catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    throw JavaScriptException(ScriptRealm.current(context).errorIn(active, "invalid tool argument"), "script-tool", 1)
                }
                if (result == null) target else JsonScriptData(context, active, limit).convert(result)
            }, ScriptableObject.DONTENUM)
        }
        fun output(name: String, bytes: ByteArray, charset: Charset = Charsets.UTF_8): JsonElement = when {
            name.endsWith("Base64") -> JsonPrimitive(Base64.getEncoder().encodeToString(bytes))
            name.endsWith("Hex") -> JsonPrimitive(cn.hutool.core.util.HexUtil.encodeHexStr(bytes))
            name.endsWith("Str") -> JsonPrimitive(bytes.toString(charset))
            else -> ScriptTools.bytes(bytes)
        }
        if (name == "createSymmetricCrypto") {
            val bare = algorithm.substringBefore('/')
            val key = if (values[1] == JsonNull) KeyGenerator.getInstance(bare).generateKey()
                else SecretKeySpec(data(values[1]), bare)
            val crypto = SymmetricCrypto(algorithm.replace("/PKCS7Padding", "/PKCS5Padding", true), key)
            values.getOrNull(2)?.takeUnless { it == JsonNull }?.let { data(it).takeIf(ByteArray::isNotEmpty)?.let(crypto::setIv) }
            method("setIv") { a -> require(a.size == 1); crypto.setIv(data(a[0])); null }
            for (operation in listOf("encrypt", "encryptHex", "encryptBase64", "decrypt", "decryptStr")) {
                method(operation) { a ->
                    require(a.size in 1..2)
                    val charset = a.getOrNull(1)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(Charset::forName) ?: Charsets.UTF_8
                    val decrypt = operation.startsWith("decrypt")
                    val input = if (decrypt) ciphertext(a[0]) else data(a[0], charset)
                    output(operation, if (decrypt) crypto.decrypt(input) else crypto.encrypt(input), charset)
                }
            }
        } else {
            val cipher = if (name == "createAsymmetricCrypto") AsymmetricCrypto(algorithm) else null
            val signer = if (name == "createSign") Sign(algorithm) else null
            val keyAlgorithm = if (cipher != null) algorithm.substringBefore('/') else KeyUtil.getAlgorithmAfterWith(algorithm)
            method("setPrivateKey") { a ->
                require(a.size == 1)
                val key = KeyUtil.generatePrivateKey(keyAlgorithm, data(a[0]))
                cipher?.setPrivateKey(key) ?: signer!!.setPrivateKey(key)
                null
            }
            method("setPublicKey") { a ->
                require(a.size == 1)
                val key = KeyUtil.generatePublicKey(keyAlgorithm, data(a[0]))
                cipher?.setPublicKey(key) ?: signer!!.setPublicKey(key)
                null
            }
            method("getPrivateKeyBase64") { a -> require(a.isEmpty()); JsonPrimitive(cipher?.privateKeyBase64 ?: signer!!.privateKeyBase64) }
            method("getPublicKeyBase64") { a -> require(a.isEmpty()); JsonPrimitive(cipher?.publicKeyBase64 ?: signer!!.publicKeyBase64) }
            if (cipher != null) {
                method("setEncryptBlockSize") { a -> require(a.size == 1 && a[0].jsonPrimitive.int > 0); cipher.setEncryptBlockSize(a[0].jsonPrimitive.int); null }
                method("setDecryptBlockSize") { a -> require(a.size == 1 && a[0].jsonPrimitive.int > 0); cipher.setDecryptBlockSize(a[0].jsonPrimitive.int); null }
                for (operation in listOf("encrypt", "encryptHex", "encryptBase64", "decrypt", "decryptStr")) {
                    method(operation) { a ->
                        require(a.size in 1..2)
                        // The reference defaults BOTH directions to public; explicit null selects private.
                        val public = a.size == 1 || a[1] != JsonNull && a[1].jsonPrimitive.boolean
                        val type = if (public) KeyType.PublicKey else KeyType.PrivateKey
                        val decrypt = operation.startsWith("decrypt")
                        val bytes = if (decrypt) cipher.decrypt(ciphertext(a[0]), type) else cipher.encrypt(data(a[0]), type)
                        output(operation, bytes)
                    }
                }
            } else {
                for (operation in listOf("sign", "signHex")) method(operation) { a ->
                    require(a.size in 1..2)
                    val charset = a.getOrNull(1)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(Charset::forName) ?: Charsets.UTF_8
                    output(operation, signer!!.sign(data(a[0], charset)))
                }
                method("verify") { a -> require(a.size == 2); JsonPrimitive(signer!!.verify(data(a[0]), ciphertext(a[1]))) }
            }
        }
        return target
    }

    private fun data(value: JsonElement, charset: Charset = Charsets.UTF_8): ByteArray = when (value) {
        is JsonArray -> ScriptTools.Arguments(listOf(value)).bytes(0)
        is JsonPrimitive -> { require(value.isString); value.content.toByteArray(charset) }
        else -> error("Byte or string data required")
    }

    private fun ciphertext(value: JsonElement): ByteArray = if (value is JsonPrimitive && value.isString) {
        val text = value.content
        if (text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) ScriptTools.unhex(text) ?: byteArrayOf()
        else ScriptTools.decodeBase64(text, 0, true)
    } else data(value)
}
