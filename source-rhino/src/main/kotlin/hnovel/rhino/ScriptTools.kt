package hnovel.rhino

import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pure JsExtensions/JsEncodeUtils operations. These stay in the worker, without host capabilities. */
internal object ScriptTools {
    val methods = setOf("strToBytes", "bytesToStr", "base64Encode", "base64Decode", "base64DecodeToByteArray",
        "hexDecodeToByteArray", "hexDecodeToString", "hexEncodeToString", "md5Encode", "md5Encode16",
        "digestHex", "digestBase64Str", "HMacHex", "HMacBase64", "encodeURI", "timeFormatUTC", "timeFormat", "randomUUID") + ScriptCrypto.methods

    fun call(name: String, values: List<JsonElement>): JsonElement {
        val args = Arguments(values)
        fun digest(algorithm: String) = MessageDigest.getInstance(algorithm).digest(args.text(0).toByteArray(Charsets.UTF_8))
        fun hmac(): ByteArray {
            val algorithm = args.text(1)
            return Mac.getInstance(algorithm).apply {
                init(SecretKeySpec(args.text(2).toByteArray(Charsets.UTF_8), algorithm))
            }.doFinal(args.text(0).toByteArray(Charsets.UTF_8))
        }
        return when (name) {
            "strToBytes" -> { args.count(1, 2); bytes(args.text(0).toByteArray(args.charset(1))) }
            "bytesToStr" -> { args.count(1, 2); JsonPrimitive(args.bytes(0).toString(args.charset(1))) }
            "hexEncodeToString" -> { args.count(1); JsonPrimitive(hex(args.text(0).toByteArray(Charsets.UTF_8))) }
            "hexDecodeToString", "hexDecodeToByteArray" -> {
                args.count(1)
                val text = args.text(0)
                val decoded = unhex(text)
                if (name == "hexDecodeToString") JsonPrimitive(decoded?.toString(Charsets.UTF_8) ?: "")
                else decoded?.let(::bytes) ?: JsonNull
            }
            "md5Encode", "md5Encode16" -> {
                args.count(1)
                val encoded = hex(digest("MD5"))
                JsonPrimitive(if (name == "md5Encode16") encoded.substring(8, 24) else encoded)
            }
            "digestHex", "digestBase64Str" -> {
                args.count(2)
                val value = digest(args.text(1))
                JsonPrimitive(if (name == "digestHex") hex(value) else encodeBase64(value, 2))
            }
            "HMacHex", "HMacBase64" -> {
                args.count(3)
                val value = hmac()
                JsonPrimitive(if (name == "HMacHex") hex(value) else encodeBase64(value, 2))
            }
            "base64Encode" -> {
                args.count(1, 2)
                JsonPrimitive(encodeBase64(args.text(0).toByteArray(Charsets.UTF_8), args.flags(1, 2)))
            }
            "base64Decode", "base64DecodeToByteArray" -> {
                args.count(1, 2)
                val charsetArgument = name == "base64Decode" && values.getOrNull(1)?.jsonPrimitive?.isString == true
                val hutool = name == "base64Decode" && (values.size == 1 || charsetArgument)
                if (values[0] == JsonNull && (hutool || name == "base64DecodeToByteArray")) {
                    JsonNull
                } else {
                    val text = args.text(0)
                    if (name == "base64DecodeToByteArray" && text.isBlank()) return JsonNull
                    // The one-argument/string-charset overload uses Hutool's mixed-alphabet decoder.
                    val flags = if (charsetArgument) 0 else args.flags(1, 0)
                    val decoded = decodeBase64(text, flags, hutool)
                    if (name == "base64DecodeToByteArray") bytes(decoded)
                    else JsonPrimitive(decoded.toString(if (charsetArgument) args.charset(1) else Charsets.UTF_8))
                }
            }
            "encodeURI" -> {
                args.count(1, 2)
                val text = args.text(0)
                val charset = if (values.size == 2) args.text(1) else "UTF-8"
                JsonPrimitive(try { URLEncoder.encode(text, charset) } catch (_: java.io.UnsupportedEncodingException) { "" })
            }
            "timeFormatUTC" -> {
                args.count(3)
                val format = SimpleDateFormat(args.text(1), Locale.getDefault())
                format.timeZone = SimpleTimeZone(values[2].jsonPrimitive.int, "UTC")
                JsonPrimitive(format.format(Date(values[0].jsonPrimitive.long)))
            }
            "timeFormat" -> {
                args.count(1)
                JsonPrimitive(SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(values[0].jsonPrimitive.long)))
            }
            "randomUUID" -> { args.count(0); JsonPrimitive(java.util.UUID.randomUUID().toString()) }
            else -> if (name in ScriptCrypto.methods) ScriptCrypto.call(name, values) else error("Unknown pure tool")
        }
    }

    internal fun bytes(value: ByteArray) = JsonArray(value.map { JsonPrimitive(it.toInt()) })
    private fun hex(value: ByteArray): String = buildString(value.size * 2) {
        val digits = "0123456789abcdef"
        value.forEach { append(digits[(it.toInt() ushr 4) and 15]); append(digits[it.toInt() and 15]) }
    }
    internal fun unhex(value: String): ByteArray? {
        if (value.isEmpty()) return null
        // Base16Codec in the pinned Hutool version removes these blank characters.
        val clean = value.filterNot { it.isWhitespace() || it in "\uFEFF\u202A\u0000\u3164\u2800\u180E" }
        // Hutool accepts odd lengths by padding the leading nibble with zero.
        val text = if (clean.length % 2 == 0) clean else "0$clean"
        return ByteArray(text.length / 2) { i ->
            ((text[i * 2].digitToInt(16) shl 4) or text[i * 2 + 1].digitToInt(16)).toByte()
        }
    }
    private fun encodeBase64(value: ByteArray, flags: Int): String {
        val base = if (flags and 8 != 0) Base64.getUrlEncoder() else Base64.getEncoder()
        val encoded = (if (flags and 1 != 0) base.withoutPadding() else base).encodeToString(value)
        if (flags and 2 != 0 || encoded.isEmpty()) return encoded
        val newline = if (flags and 4 != 0) "\r\n" else "\n"
        return encoded.chunked(76).joinToString(newline, postfix = newline)
    }
    internal fun decodeBase64(value: String, flags: Int, mixedAlphabet: Boolean): ByteArray {
        if (mixedAlphabet) {
            // Hutool skips non-alphabet characters (including padding) and drops an incomplete byte.
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val output = java.io.ByteArrayOutputStream()
            var bits = 0
            var available = 0
            value.forEach { char ->
                val digit = alphabet.indexOf(when (char) { '-' -> '+'; '_' -> '/'; else -> char })
                if (digit >= 0) {
                    bits = (bits shl 6) or digit
                    available += 6
                    if (available >= 8) { available -= 8; output.write(bits ushr available) }
                }
            }
            return output.toByteArray()
        }
        val url = flags and 8 != 0
        val alphabet = if (url) "-_" else "+/"
        // Android Base64 ignores bytes outside the selected alphabet.
        val filtered = value.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in alphabet || it == '=' }
        return (if (url) Base64.getUrlDecoder() else Base64.getDecoder()).decode(filtered)
    }

    internal class Arguments(private val values: List<JsonElement>) {
        fun count(min: Int, max: Int = min) = require(values.size in min..max) { "Invalid argument count" }
        fun text(index: Int): String {
            val value = values[index].jsonPrimitive
            require(value.isString) { "String argument required" }
            return value.content
        }
        fun charset(index: Int) = Charset.forName(values.getOrNull(index)?.let { text(index) } ?: "UTF-8")
        fun flags(index: Int, default: Int): Int = (values.getOrNull(index)?.jsonPrimitive?.int ?: default).also {
            require(it and 31 == it) { "Unknown Base64 flags" }
        }
        fun bytes(index: Int) = values[index].jsonArray.map {
            val value = it.jsonPrimitive.int
            require(value in -128..255) { "Byte outside range" }
            value.toByte()
        }.toByteArray()
    }
}
