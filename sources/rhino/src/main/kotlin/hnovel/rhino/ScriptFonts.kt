package hnovel.rhino

import hnovel.rhino.font.QueryTTF
import kotlinx.serialization.json.*
import org.mozilla.javascript.*
import java.security.MessageDigest

/** Font parsing stays in the worker. Only explicit data methods reach the script. */
internal class ScriptFonts(private val readUrl: (Context, String) -> ByteArray) {
    private val cache = linkedMapOf<String, QueryTTF>()
    companion object { private val handle = Any() }
    val methods = setOf("queryTTF", "queryBase64TTF", "replaceFont")

    fun call(cx: Context, scope: Scriptable, name: String, raw: Array<out Any>): Any? {
        val limit = cx.getThreadLocal(bridgeLimitKey) as Int
        val realm = ScriptRealm.current(cx)
        fun font(value: Any?): QueryTTF? {
            if (value == null || Undefined.isUndefined(value)) return null
            return (value as? ScriptableObject)?.getAssociatedValue(handle) as? QueryTTF
                ?: error("Font handle required")
        }
        if (name == "replaceFont") {
            require(raw.size in 3..4 && raw[0] is CharSequence)
            val text = raw[0].toString()
            if (text.length > limit) throw ResultTooLarge()
            val from = font(raw[1]); val to = font(raw[2])
            val filter = raw.size == 4 && Context.toBoolean(raw[3])
            val result = if (from == null || to == null) text else buildString {
                var offset = 0
                while (offset < text.length) {
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    val point = text.codePointAt(offset)
                    offset += Character.charCount(point)
                    var glyph = from.getGlyfByUnicode(point)
                    if (from.getGlyfIdByUnicode(point) == 0) glyph = null
                    when {
                        from.isBlankUnicode(point) -> appendCodePoint(point)
                        filter && glyph == null -> Unit
                        else -> appendCodePoint(to.getUnicodeByGlyf(glyph).takeIf { it != 0 } ?: point)
                    }
                    if (length > limit) throw ResultTooLarge()
                }
            }
            return JsonScriptData(cx, scope, limit).convert(JsonPrimitive(result))
        }
        require(raw.size in 1..if (name == "queryTTF") 2 else 1)
        val args = Json.parseToJsonElement(BoundedJsonResult(limit).encode(realm.arrayIn(scope, raw))).jsonArray
        val input = args[0]
        if (input == JsonNull) return null
        val useCache = args.size == 1 || args[1].jsonPrimitive.boolean
        val key = MessageDigest.getInstance("SHA-256").digest(input.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val parser = (if (useCache) cache[key] else null) ?: run {
            val bytes = when (input) {
                is JsonArray -> ScriptTools.Arguments(args).bytes(0)
                is JsonPrimitive -> {
                    if (!input.isString) return null
                    val text = input.content
                    if (text.isBlank()) return null
                    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)) readUrl(cx, text)
                    else ScriptTools.decodeBase64(text, 0, false)
                }
                else -> return null
            }
            if (bytes.size > limit) throw ResultTooLarge()
            QueryTTF(bytes).also {
                if (useCache) {
                    if (cache.size >= 4) cache.remove(cache.keys.first())
                    cache[key] = it
                }
            }
        }
        return realm.objectIn(scope).apply {
            associateValue(handle, parser)
            for (member in listOf("getGlyfById", "getGlyfIdByUnicode", "getGlyfByUnicode", "getUnicodeByGlyf", "isBlankUnicode")) {
                defineProperty(member, realm.method(scope) { context, active, values ->
                    val currentLimit = context.getThreadLocal(bridgeLimitKey) as Int
                    try {
                        val data = Json.parseToJsonElement(BoundedJsonResult(currentLimit).encode(ScriptRealm.current(context).arrayIn(active, values))).jsonArray
                        require(data.size == 1)
                        val result = when (member) {
                            "getGlyfById" -> parser.getGlyfById(data[0].jsonPrimitive.int)?.let(::JsonPrimitive) ?: JsonNull
                            "getGlyfIdByUnicode" -> JsonPrimitive(parser.getGlyfIdByUnicode(data[0].jsonPrimitive.int))
                            "getGlyfByUnicode" -> parser.getGlyfByUnicode(data[0].jsonPrimitive.int)?.let(::JsonPrimitive) ?: JsonNull
                            "getUnicodeByGlyf" -> JsonPrimitive(parser.getUnicodeByGlyf(data[0].takeUnless { it == JsonNull }?.jsonPrimitive?.content))
                            else -> JsonPrimitive(parser.isBlankUnicode(data[0].jsonPrimitive.int))
                        }
                        JsonScriptData(context, active, currentLimit).convert(result)
                    } catch (large: ResultTooLarge) { throw large }
                    catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                    catch (_: Exception) { throw JavaScriptException(ScriptRealm.current(context).errorIn(active, "invalid font argument"), "script-font", 1) }
                }, ScriptableObject.DONTENUM)
            }
        }
    }
}
