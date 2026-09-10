package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.Context
import java.nio.charset.Charset

/** Byte decoding is worker work. The broker remains the only resource authority. */
internal class ScriptResources(private val bridge: HostBridge, private val requests: ScriptRequestTemplates,
    private val archives: ArchiveDecoder) {
    val methods = setOf("unzipFile", "un7zFile", "unrarFile", "unArchiveFile", "getTxtInFolder",
        "getZipStringContent", "getZipByteArrayContent", "getRarStringContent", "getRarByteArrayContent",
        "get7zStringContent", "get7zByteArrayContent")
    fun read(cx: Context, path: String): ByteArray? {
        val data = bridge.call("java.readFile", listOf(JsonPrimitive(path)))
        if (data == JsonNull) return null
        val limit = cx.getThreadLocal(bridgeLimitKey) as Int
        if (data.toString().length > limit) throw ResultTooLarge()
        return ScriptTools.Arguments(listOf(data)).bytes(0)
    }

    fun download(cx: Context, url: String): ByteArray {
        val path = bridge.call("java.downloadFile", requests.prepare(cx, "java.downloadFile", listOf(JsonPrimitive(url))))
        return read(cx, path.jsonPrimitive.content) ?: error("Resource missing")
    }

    fun text(cx: Context, args: List<JsonElement>): JsonElement {
        require(args.size in 1..2 && args[0].jsonPrimitive.isString)
        val bytes = read(cx, args[0].jsonPrimitive.content) ?: return JsonPrimitive("")
        val charset = if (args.size == 2) args[1].jsonPrimitive.content else ScriptText.charset(bytes, fileSample = true)
        return JsonPrimitive(bytes.toString(Charset.forName(charset)))
    }

    fun archive(cx: Context, name: String, args: List<JsonElement>): JsonElement {
        val limit = cx.getThreadLocal(bridgeLimitKey) as Int
        val first = args.first().jsonPrimitive.content
        if (name == "getTxtInFolder") {
            require(args.size == 1)
            if (first.isEmpty()) return JsonPrimitive("")
            val data = bridge.call("resource.readArchive", args)
            if (data == JsonNull) return JsonPrimitive("")
            if (data.toString().length > limit) throw ResultTooLarge()
            val files = data.jsonObject
            // Pinned folder helper reads immediate children, then consumes the folder.
            require(files.keys.none { '/' in it })
            val result = files.values.joinToString("\n") {
                val bytes = java.util.Base64.getDecoder().decode(it.jsonPrimitive.content)
                bytes.toString(Charset.forName(ScriptText.charset(bytes, fileSample = true)))
            }
            val value = JsonPrimitive(result)
            if (value.toString().length > limit) throw ResultTooLarge()
            bridge.call("java.deleteFile", args)
            return value
        }
        if (name.startsWith("un")) {
            require(args.size == 1)
            if (first.isEmpty()) return JsonPrimitive("")
            val files = archives.decode(read(cx, first) ?: error("Archive missing"), limit)
            val snapshot = JsonObject(files.mapValues { JsonPrimitive(java.util.Base64.getEncoder().encodeToString(it.value)) })
            if (snapshot.toString().length > limit) throw ResultTooLarge()
            return bridge.call("resource.storeArchive", listOf(JsonPrimitive(first), snapshot))
        }
        val string = name.endsWith("StringContent")
        require(args.size in 2..if (string) 3 else 2)
        val bytes = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(first)) download(cx, first)
            else ScriptTools.unhex(first) ?: byteArrayOf()
        val path = args[1].jsonPrimitive.content
        ArchiveDecoder.validatePath(path)
        val content = archives.decode(bytes, limit)[path]
        if (!string) return content?.let(ScriptTools::bytes) ?: JsonNull
        if (content == null) return JsonPrimitive("")
        val charset = args.getOrNull(2)?.jsonPrimitive?.content ?: ScriptText.charset(content)
        return JsonPrimitive(content.toString(Charset.forName(charset)))
    }
}
