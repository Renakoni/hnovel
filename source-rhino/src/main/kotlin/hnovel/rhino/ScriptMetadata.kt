package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.*

/** Invocation snapshots. Metadata never supplies source identity or host execution authority. */
internal object ScriptMetadata {
    fun create(cx: Context, scope: Scriptable, data: JsonObject, id: String?, chapter: Boolean,
        variables: MutableMap<String, String>, writes: MutableMap<String, String?>): ScriptableObject {
        val realm = ScriptRealm.current(cx)
        val limit = cx.getThreadLocal(bridgeLimitKey) as Int
        val defaults = buildJsonObject {
            val strings = if (chapter) "url title baseUrl bookUrl" else "bookUrl tocUrl origin originName name author"
            strings.split(' ').forEach { put(it, "") }
            val nullable = if (chapter) "resourceUrl tag wordCount start end startFragmentId endFragmentId variable" else
                "kind customTag coverUrl customCoverUrl intro customIntro charset latestChapterTitle durChapterTitle wordCount variable infoHtml tocHtml readConfig"
            nullable.split(' ').forEach { put(it, JsonNull) }
            val numbers = if (chapter) "index" else "type group latestChapterTime lastCheckTime lastCheckCount totalChapterNum durChapterIndex durChapterPos durChapterTime order originOrder syncTime"
            numbers.split(' ').forEach { put(it, 0) }
            if (chapter) listOf("isVolume", "isVip", "isPay").forEach { put(it, false) } else put("canUpdate", true)
        }
        val result = JsonScriptData(cx, scope, limit).convert(data) as ScriptableObject
        defaults.forEach { (name, value) -> if (!result.has(name, result)) {
            val primitive = value.jsonPrimitive
            result.defineProperty(name, when { value == JsonNull -> null; primitive.isString -> primitive.content; primitive.booleanOrNull != null -> primitive.boolean; else -> primitive.int }, ScriptableObject.EMPTY)
        } }
        if (id != null) result.defineProperty("id", id, ScriptableObject.READONLY)
        fun text(name: String): String = ScriptableObject.getProperty(result, name).let { if (it == null || Undefined.isUndefined(it)) "" else Context.toString(it) }
        fun method(name: String, action: (List<JsonElement>) -> JsonElement) {
            result.defineProperty(name, realm.method(scope) { context, active, raw ->
                val currentLimit = context.getThreadLocal(bridgeLimitKey) as Int
                try {
                    val args = Json.parseToJsonElement(BoundedJsonResult(currentLimit).encode(ScriptRealm.current(context).arrayIn(active, raw))).jsonArray
                    JsonScriptData(context, active, currentLimit).convert(action(args))
                } catch (large: ResultTooLarge) { throw large }
                catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (_: Exception) { throw JavaScriptException(ScriptRealm.current(context).errorIn(active, "invalid metadata argument"), "script-metadata", 1) }
            }, ScriptableObject.DONTENUM)
        }
        // Small and large values share the bounded invocation map. Host persistence decides storage layout.
        fun put(key: String, value: String?): JsonElement {
            val next = variables.toMutableMap().apply { if (value == null) remove(key) else this[key] = value }
            val json = JsonObject(next.mapValues { JsonPrimitive(it.value) })
            JsonScriptData(Context.getCurrentContext(), scope, Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int).convert(json)
            variables.clear(); variables.putAll(next); writes[key] = value
            result.put("variable", result, json.toString())
            return JsonPrimitive(true)
        }
        method("getVariable") { a -> require(a.size == 1); JsonPrimitive(variables[a[0].jsonPrimitive.content].orEmpty()) }
        method("putVariable") { a -> require(a.size == 2); put(a[0].jsonPrimitive.content, a[1].takeUnless { it == JsonNull }?.jsonPrimitive?.content) }
        if (!chapter) {
            method("getCustomVariable") { a -> require(a.isEmpty()); JsonPrimitive(variables["custom"].orEmpty()) }
            method("putCustomVariable") { a -> require(a.size == 1); put("custom", a[0].takeUnless { it == JsonNull }?.jsonPrimitive?.content); JsonNull }
            method("getKindList") { a ->
                require(a.isEmpty())
                JsonArray((listOf(text("wordCount")).filter(String::isNotBlank) + text("kind").split(',', '\n').filter(String::isNotBlank)).map(::JsonPrimitive))
            }
        } else {
            method("primaryStr") { a -> require(a.isEmpty()); JsonPrimitive(text("bookUrl") + text("url")) }
            method("getAbsoluteURL") { a ->
                require(a.isEmpty())
                val url = text("url"); val base = text("baseUrl")
                if (Context.toBoolean(ScriptableObject.getProperty(result, "isVolume")) && url.startsWith(text("title"))) JsonPrimitive(base)
                else {
                    val split = Regex(",\\s*(?=\\{)").find(url)
                    val before = split?.let { url.substring(0, it.range.first) } ?: url
                    val resolved = java.net.URL(java.net.URL(base), before).toString()
                    JsonPrimitive(resolved + (split?.let { "," + url.substring(it.range.last + 1) } ?: ""))
                }
            }
        }
        return result
    }
}
