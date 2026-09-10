package hnovel.rhino

import kotlinx.serialization.json.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Legacy JsEncodeUtils string/byte helpers, including the pinned overloads' asymmetric behavior. */
internal object ScriptCrypto {
    val methods = setOf("aesDecodeToByteArray", "aesDecodeToString", "aesDecodeArgsBase64Str",
        "aesBase64DecodeToByteArray", "aesBase64DecodeToString", "aesEncodeToByteArray", "aesEncodeToString",
        "aesEncodeToBase64ByteArray", "aesEncodeToBase64String", "aesEncodeArgsBase64Str", "desDecodeToString",
        "desBase64DecodeToString", "desEncodeToString", "desEncodeToBase64String", "tripleDESDecodeStr",
        "tripleDESDecodeArgsBase64Str", "tripleDESEncodeBase64Str", "tripleDESEncodeArgsBase64Str")

    fun call(name: String, values: List<JsonElement>): JsonElement {
        val args = ScriptTools.Arguments(values)
        val triple = name.startsWith("tripleDES")
        val splitMode = triple || name.endsWith("ArgsBase64Str")
        args.count(if (splitMode) 5 else 4)
        val transformation = if (splitMode) "${if (triple) "DESede" else "AES"}/${args.text(2)}/${args.text(3)}" else args.text(2)
        val ivText = args.text(if (splitMode) 4 else 3)
        // The pinned AES encode-args overload keeps the raw key/IV strings. Its decode overload
        // decodes both; the 3DES args overloads decode only the key. Do not silently 'fix' these.
        val decodeKey = name == "aesDecodeArgsBase64Str" || triple && name.contains("ArgsBase64")
        val key = if (decodeKey) ScriptTools.decodeBase64(args.text(1), 2, false) else args.text(1).toByteArray(Charsets.UTF_8)
        val iv = if (name == "aesDecodeArgsBase64Str") ScriptTools.decodeBase64(ivText, 2, false) else ivText.toByteArray(Charsets.UTF_8)
        // aesEncodeToString in the reference actually decrypts; desEncodeToString converts raw
        // encrypted bytes to UTF-8. Existing sources can observe both historical contracts.
        val decrypt = name.contains("Decode") || name == "aesEncodeToString"
        val data = if (decrypt) {
            val text = args.text(0)
            if (text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) ScriptTools.unhex(text) ?: byteArrayOf()
            else ScriptTools.decodeBase64(text, 0, true)
        } else args.text(0).toByteArray(Charsets.UTF_8)
        val output = crypt(data, key, iv, transformation, decrypt)
        return when {
            name.endsWith("Base64ByteArray") -> ScriptTools.bytes(java.util.Base64.getEncoder().encode(output))
            name.endsWith("ByteArray") -> ScriptTools.bytes(output)
            !decrypt && name != "desEncodeToString" -> JsonPrimitive(java.util.Base64.getEncoder().encodeToString(output))
            else -> JsonPrimitive(output.toString(Charsets.UTF_8))
        }
    }

    private fun crypt(data: ByteArray, key: ByteArray, iv: ByteArray, transformation: String, decrypt: Boolean): ByteArray {
        val zeroPadding = transformation.endsWith("/ZeroPadding", true)
        val algorithm = transformation.replace("/ZeroPadding", "/NoPadding", true).replace("/PKCS7Padding", "/PKCS5Padding", true)
        val cipher = Cipher.getInstance(algorithm)
        val secret = SecretKeySpec(key, transformation.substringBefore('/'))
        val mode = if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
        if (iv.isEmpty()) cipher.init(mode, secret) else cipher.init(mode, secret, IvParameterSpec(iv))
        val input = if (zeroPadding && !decrypt && data.size % cipher.blockSize != 0)
            data.copyOf(data.size + cipher.blockSize - data.size % cipher.blockSize) else data
        val output = cipher.doFinal(input)
        return if (zeroPadding && decrypt) output.copyOf(output.indexOfLast { it != 0.toByte() } + 1) else output
    }
}
