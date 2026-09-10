package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.*

/** Invocation snapshots. Metadata never supplies source identity or host execution authority. */
internal object ScriptMetadata {
    private val captureKey = Any()
    private data class Capture(val initial: JsonObject, val defaults: JsonObject)
    fun capture(value: ScriptableObject, maxChars: Int): JsonObject {
        val original = value.getAssociatedValue(captureKey) as Capture
        val changed = mutableMapOf<String, JsonElement>()
        for ((key, before) in original.defaults + original.initial) {
            val after = Json.parseToJsonElement(BoundedJsonResult(maxChars).encode(ScriptableObject.getProperty(value, key)))
            if (after != before) changed[key] = after
        }
        return JsonObject(original.initial + changed).also {
            JsonScriptData(Context.getCurrentContext(), value.parentScope, maxChars).convert(it)
        }
    }
    fun create(cx: Context, scope: Scriptable, data: JsonObject, id: String?, chapter: Boolean,
        variables: MutableMap<String, String>, writes: MutableMap<String, String?>,
        bigVariables: MutableMap<String, String> = mutableMapOf(), bigWrites: MutableMap<String, String?> = mutableMapOf(),
        chineseConverter: Int = 0, initializeFromVariable: Boolean = true, initialized: () -> Unit = {}): ScriptableObject {
        val realm = ScriptRealm.current(cx)
        val limit = cx.getThreadLocal(bridgeLimitKey) as Int
        val defaults = buildJsonObject {
            val strings = if (chapter) "url title baseUrl bookUrl" else "bookUrl tocUrl origin originName name author"
            strings.split(' ').forEach { put(it, "") }
            val nullable = if (chapter) "resourceUrl tag wordCount start end startFragmentId endFragmentId variable titleMD5" else
                "kind customTag coverUrl customCoverUrl intro customIntro charset latestChapterTitle durChapterTitle wordCount variable infoHtml tocHtml readConfig"
            nullable.split(' ').forEach { put(it, JsonNull) }
            val numbers = if (chapter) "index" else "type group latestChapterTime lastCheckTime lastCheckCount totalChapterNum durChapterIndex durChapterPos durChapterTime order originOrder syncTime"
            numbers.split(' ').forEach { put(it, 0) }
            if (chapter) listOf("isVolume", "isVip", "isPay").forEach { put(it, false) } else put("canUpdate", true)
        }
        val result = JsonScriptData(cx, scope, limit).convert(data) as ScriptableObject
        result.associateValue(captureKey, Capture(data, defaults))
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
        for ((name, default) in defaults + data) {
            if (name == "id") continue
            val suffix = name.replaceFirstChar { it.uppercaseChar() }
            method("get$suffix") { a -> require(a.isEmpty()); Json.parseToJsonElement(BoundedJsonResult(Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int).encode(ScriptableObject.getProperty(result, name))) }
            method("set$suffix") { a ->
                require(a.size == 1)
                if (default != JsonNull) {
                    val v = a[0].jsonPrimitive; val expected = default.jsonPrimitive
                    require(if (expected.isString) v.isString else if (expected.booleanOrNull != null) v.booleanOrNull != null else v.doubleOrNull != null)
                }
                result.put(name, result, JsonScriptData(Context.getCurrentContext(), scope, Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int).convert(a[0])); JsonNull
            }
        }
        var lastSmall = variables.toMap()
        val map by lazy {
            if (initializeFromVariable) {
                val initial = runCatching { Json.parseToJsonElement(text("variable")).jsonObject }.getOrNull()
                initial?.forEach { (key, value) -> if (value is JsonPrimitive && value.isString) variables.putIfAbsent(key, value.content) }
                initialized()
            }
            lastSmall = variables.toMap()
            ScriptData.map(Context.getCurrentContext(), scope, variables, changed = {
            (lastSmall.keys + variables.keys).forEach { key -> if (lastSmall[key] != variables[key]) writes[key] = variables[key] }
            lastSmall = variables.toMap()
        }, validate = { values -> require(values.values.all { it == null || it is String }) }) }
        result.defineProperty("variableMap", java.util.function.Supplier<Any> { map }, null, ScriptableObject.DONTENUM)
        result.defineProperty("getVariableMap", ScriptCalls.method(scope, "invalid metadata argument") { _, _, a -> require(a.isEmpty()); map }, ScriptableObject.DONTENUM)
        // The reference's 10,000-character threshold moves values between two stores.
        fun put(key: String, value: String?): JsonElement {
            map // Initialize the reference's lazy variableMap before mutation.
            val existed = variables.containsKey(key)
            val small = value != null && value.length < 10000
            val next = variables.toMutableMap().apply { if (small) this[key] = value else remove(key) }
            val nextBig = bigVariables.toMutableMap().apply { if (value != null && !small) this[key] = value else remove(key) }
            val json = JsonObject(next.mapValues { JsonPrimitive(it.value) })
            JsonScriptData(Context.getCurrentContext(), scope, Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int).convert(json)
            JsonScriptData(Context.getCurrentContext(), scope, Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int).convert(JsonObject(nextBig.mapValues { JsonPrimitive(it.value) }))
            variables.clear(); variables.putAll(next); writes[key] = next[key]
            bigVariables.clear(); bigVariables.putAll(nextBig); bigWrites[key] = nextBig[key]
            lastSmall = variables.toMap()
            if (small || existed) result.put("variable", result, json.toString())
            return JsonPrimitive(true)
        }
        method("getVariable") { a -> require(a.size in 0..1); if (a.isEmpty()) ScriptData.json(ScriptableObject.getProperty(result, "variable")) else { map; JsonPrimitive((variables[a[0].jsonPrimitive.content] ?: bigVariables[a[0].jsonPrimitive.content]).orEmpty()) } }
        method("putVariable") { a -> require(a.size == 2); put(a[0].jsonPrimitive.content, a[1].takeUnless { it == JsonNull }?.jsonPrimitive?.content) }
        method("getBigVariable") { a -> require(a.size == 1); bigVariables[a[0].jsonPrimitive.content]?.let(::JsonPrimitive) ?: JsonNull }
        method("putBigVariable") { a ->
            require(a.size == 2); val key = a[0].jsonPrimitive.content; val value = a[1].takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val next = bigVariables.toMutableMap().apply { if (value == null) remove(key) else this[key] = value }
            JsonScriptData(Context.getCurrentContext(), scope, Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int).convert(JsonObject(next.mapValues { JsonPrimitive(it.value) }))
            bigVariables.clear(); bigVariables.putAll(next); bigWrites[key] = value; JsonNull
        }
        if (!chapter) {
            var folder: String? = null
            method("getFolderName") { a -> require(a.isEmpty()); JsonPrimitive(folder ?: (text("name").replace(Regex("[\\\\/:*?\"<>|.]"), "").take(9) + java.security.MessageDigest.getInstance("MD5").digest(text("bookUrl").toByteArray()).joinToString("") { "%02x".format(it) }.substring(8, 24)).also { folder = it }) }
            method("getRealAuthor") { a -> require(a.isEmpty()); JsonPrimitive(text("author").replace(Regex("^\\s*作\\s*者[:：\\s]+|\\s+著"), "")) }
            method("getLastChapterIndex") { a -> require(a.isEmpty()); JsonPrimitive(Context.toNumber(ScriptableObject.getProperty(result, "totalChapterNum")).toInt() - 1) }
            method("getUnreadChapterNum") { a -> require(a.isEmpty()); JsonPrimitive(maxOf(0, Context.toNumber(ScriptableObject.getProperty(result, "totalChapterNum")).toInt() - Context.toNumber(ScriptableObject.getProperty(result, "durChapterIndex")).toInt() - 1)) }
            for ((name, custom, original) in listOf(Triple("getDisplayCover", "customCoverUrl", "coverUrl"), Triple("getDisplayIntro", "customIntro", "intro"))) {
                method(name) { a -> require(a.isEmpty()); val key = if (text(custom).isEmpty()) original else custom; ScriptData.json(ScriptableObject.getProperty(result, key)) }
            }
            method("upCustomIntro") { a -> require(a.isEmpty()); result.put("customIntro", result, ScriptableObject.getProperty(result, "intro")); JsonNull }
            result.defineProperty("fileCharset", ScriptCalls.method(scope, "invalid metadata argument") { context, active, a ->
                require(a.isEmpty()); ScriptData.charset(context, active, java.nio.charset.Charset.forName(text("charset").ifEmpty { "UTF-8" }))
            }, ScriptableObject.DONTENUM)
            for (name in listOf("toSearchBook", "toBook")) result.defineProperty(name, ScriptCalls.method(scope, "invalid metadata argument") { context, active, a ->
                require(a.isEmpty())
                val fields = "name author kind bookUrl origin originName type wordCount latestChapterTitle coverUrl intro tocUrl originOrder variable infoHtml tocHtml".split(' ')
                val snapshot = buildJsonObject { fields.forEach { key -> put(key, Json.parseToJsonElement(BoundedJsonResult(context.getThreadLocal(bridgeLimitKey) as Int).encode(ScriptableObject.getProperty(result, key)))) } }
                create(context, active, snapshot, null, false, mutableMapOf(), mutableMapOf(), bigVariables.toMutableMap(), mutableMapOf(), chineseConverter)
            }, ScriptableObject.DONTENUM)
            method("getCustomVariable") { a -> require(a.isEmpty()); map; JsonPrimitive((variables["custom"] ?: bigVariables["custom"]).orEmpty()) }
            method("putCustomVariable") { a -> require(a.size == 1); put("custom", a[0].takeUnless { it == JsonNull }?.jsonPrimitive?.content); JsonNull }
            result.defineProperty("getKindList", ScriptCalls.method(scope, "invalid metadata argument") { context, active, a ->
                require(a.isEmpty())
                ScriptData.list(context, active, (listOf(text("wordCount")).filter(String::isNotBlank) + text("kind").split(',', '\n').filter(String::isNotBlank)).toMutableList())
            }, ScriptableObject.DONTENUM)
        } else {
            fun fileName(suffix: String): String {
                val hash = ScriptableObject.getProperty(result, "titleMD5")?.let { Context.toString(it) }
                    ?: java.security.MessageDigest.getInstance("MD5").digest(text("title").toByteArray())
                    .joinToString("") { "%02x".format(it) }.substring(8, 24).also { result.put("titleMD5", result, it) }
                return String.format(java.util.Locale.getDefault(), "%05d-%s.%s", Context.toNumber(ScriptableObject.getProperty(result, "index")).toInt(), hash, suffix)
            }
            method("getFileName") { a -> require(a.size in 0..1); JsonPrimitive(fileName(a.firstOrNull()?.jsonPrimitive?.content ?: "nb")) }
            method("getFontName") { a -> require(a.isEmpty()); JsonPrimitive(fileName("ttf")) }
            method("getDisplayTitle") { a ->
                require(a.size in 0..3)
                var title = text("title").replace(Regex("[\\r\\n]"), "")
                if (a.getOrNull(2)?.jsonPrimitive?.boolean != false) title = when (chineseConverter) {
                    1 -> ScriptText.simplified(title); 2 -> ScriptText.traditional(title); else -> title
                }
                val useReplace = a.getOrNull(1)?.jsonPrimitive?.boolean ?: true
                if (useReplace) a.firstOrNull()?.takeUnless { it == JsonNull }?.jsonArray?.forEach { item ->
                    val rule = item.jsonObject; val pattern = rule["pattern"]?.jsonPrimitive?.content.orEmpty()
                    val replacement = rule["replacement"]?.jsonPrimitive?.content.orEmpty()
                    if (pattern.isNotEmpty()) {
                        val replaced = try { if (rule["isRegex"]?.jsonPrimitive?.boolean == true) title.replace(Regex(pattern), replacement) else title.replace(pattern, replacement) }
                            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                            catch (_: Exception) { title }
                        if (replaced.isNotBlank()) title = replaced
                    }
                }
                JsonPrimitive(title)
            }
            method("primaryStr") { a -> require(a.isEmpty()); JsonPrimitive(text("bookUrl") + text("url")) }
            method("getAbsoluteURL") { a ->
                require(a.isEmpty())
                val url = text("url"); val base = text("baseUrl")
                if (Context.toBoolean(ScriptableObject.getProperty(result, "isVolume")) && url.startsWith(text("title"))) JsonPrimitive(base)
                else {
                    val split = Regex(",\\s*(?=\\{)").find(url)
                    val before = split?.let { url.substring(0, it.range.first) } ?: url
                    val trimmed = before.trim()
                    val basePart = base.split(Regex(",\\s*(?=\\{)"), limit = 2).first()
                    val parsedBase = runCatching { java.net.URL(basePart) }.getOrNull()
                    val resolved = when {
                        parsedBase == null -> trimmed
                        trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) || trimmed.startsWith("data:", true) -> trimmed
                        trimmed.startsWith("javascript") -> ""
                        else -> runCatching { java.net.URL(parsedBase, trimmed).toString() }.getOrDefault(trimmed)
                    }
                    JsonPrimitive(resolved + (split?.let { "," + url.substring(it.range.last + 1) } ?: ""))
                }
            }
        }
        return result
    }
}
