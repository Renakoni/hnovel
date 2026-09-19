package hnovel.network

import hnovel.rules.RequestOptionsJson
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.nio.charset.Charset

/** Static Legado URL/options. Expressions requiring JS/browser return an explicit deferred requirement. */
class RequestCompiler {
    fun compile(id: String, rule: String, baseUrl: String, keyword: String = "", page: Int = 1,
        headers: Map<String, String> = emptyMap(), kind: ResourceKind = ResourceKind.Document,
        speakText: String? = null, speakSpeed: Int = 10, expandTemplates: Boolean = true,
        templateValues: Map<String, String> = emptyMap()): CompiledRequest {
        if (rule.length > 65536 || keyword.length > 65536 || page < 1 || (speakText?.length ?: 0) > 65536 ||
            speakText != null && speakSpeed !in 5..85) return CompiledRequest.Rejected(FailureCode.InvalidRequest)
        return try {
            if (expandTemplates && (rule.contains("<js>", true) || rule.contains("@js:", true))) return CompiledRequest.Rejected(FailureCode.ScriptRequired)
            val variables = buildMap {
                put("key", JsonPrimitive("{{key}}")); put("page", JsonPrimitive(page)); put("baseUrl", JsonPrimitive("{{baseUrl}}"))
                if (speakText != null) { put("speakText", JsonPrimitive("{{speakText}}")); put("speakSpeed", JsonPrimitive(speakSpeed)) }
                require(templateValues.size <= 1024 && templateValues.keys.none { it in this })
                templateValues.forEach { (name, _) -> put(name, JsonPrimitive("{{$name}}")) }
            }
            if (expandTemplates && Regex("\\{\\{(.*?)\\}\\}").findAll(rule).any { it.groupValues[1].trim() !in variables }) {
                return CompiledRequest.Rejected(FailureCode.ScriptRequired)
            }
            val optionStart = Regex(",\\s*(?=\\{)").find(rule)
            val options = optionStart?.let { RequestOptionsJson.options(rule.substring(it.range.last + 1), if (expandTemplates) variables else emptyMap()) } ?: buildJsonObject {}
            if (options.keys.any { it in setOf("js") }) return CompiledRequest.Rejected(FailureCode.ScriptRequired)
            if ("serverID" in options) return CompiledRequest.Rejected(FailureCode.BrowserRequired)
            if (options.keys.any { it !in setOf("method", "body", "headers", "header", "charset", "retry", "webView", "webJs", "webViewDelayTime", "type") }) return CompiledRequest.Rejected(FailureCode.UnknownOption)
            val responseAsHex = options["type"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.isNotBlank() == true
            val webView = options["webView"]?.let { it != JsonNull && it != JsonPrimitive(false) && it != JsonPrimitive("") && it != JsonPrimitive("false") } ?: false
            val webJs = options["webJs"]?.jsonPrimitive?.content.orEmpty()
            val browserDelay = options["webViewDelayTime"]?.jsonPrimitive?.long ?: 0
            if (browserDelay !in 0..30000 || webJs.length > 65536) return CompiledRequest.Rejected(FailureCode.InvalidRequest)
            val browser = if (webView || webJs.isNotBlank() || browserDelay > 0) BrowserOptions(webJs, browserDelay) else null
            val charset = options["charset"]?.jsonPrimitive?.content ?: "UTF-8"
            if (charset != "escape") Charset.forName(charset)
            fun expand(value: String, encodeKey: Boolean, pageAlternatives: Boolean = false): String {
                if (!expandTemplates) return value
                val template = Regex("\\{\\{\\s*(.*?)\\s*\\}\\}")
                // Validate the rule before substitution. Inserted keyword text is data, even
                // when it contains braces that look like another request template.
                if ("{{" in template.replace(value, "")) throw BrokerFailure(RequestStage.Parse, FailureCode.ScriptRequired)
                var text = template.replace(value) { match -> when (match.groupValues[1].trim()) {
                    "key" -> if (encodeKey) encode(keyword, charset) else keyword
                    "page" -> page.toString()
                    "baseUrl" -> baseUrl
                    "speakText" -> speakText?.let { if (encodeKey) encode(it, charset) else it }
                        ?: throw BrokerFailure(RequestStage.Parse, FailureCode.ScriptRequired)
                    "speakSpeed" -> if (speakText != null) speakSpeed.toString() else throw BrokerFailure(RequestStage.Parse, FailureCode.ScriptRequired)
                    else -> templateValues[match.groupValues[1].trim()] ?: throw BrokerFailure(RequestStage.Parse, FailureCode.ScriptRequired)
                } }
                if (pageAlternatives) text = Regex("<([^<>]+)>").replace(text) { match ->
                    val pages = match.groupValues[1].split(',')
                    pages[(page - 1).coerceAtMost(pages.lastIndex)].trim()
                }
                return text
            }
            val rawUrl = rule.substring(0, optionStart?.range?.first ?: rule.length).trim()
            val expandedUrl = expand(rawUrl, true, pageAlternatives = speakText == null)
            val url = if (expandedUrl.startsWith("data:")) expandedUrl else (baseUrl.toHttpUrlOrNull()?.resolve(encodeNonAscii(expandedUrl, charset))
                ?: encodeNonAscii(expandedUrl, charset).toHttpUrlOrNull()
                ?: return CompiledRequest.Rejected(FailureCode.InvalidRequest)).toString()
            val method = options["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
            if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) return CompiledRequest.Rejected(FailureCode.InvalidRequest)
            val mergedHeaders = headers.toMutableMap()
            val optionHeaders = options["headers"] ?: options["header"]
            val parsedHeaders = optionHeaders?.jsonObject
            parsedHeaders?.forEach { (key, value) ->
                mergedHeaders.keys.removeAll { it.equals(key, true) }
                mergedHeaders[key] = expand(value.jsonPrimitive.content, false)
            }
            val rawBody = options["body"]?.let { if (it is JsonPrimitive) it.content else it.toString() }
            val contentType = mergedHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            val isForm = rawBody != null && (contentType == null ||
                contentType.startsWith("application/x-www-form-urlencoded", ignoreCase = true)) &&
                !rawBody.trimStart().startsWith('{') && !rawBody.trimStart().startsWith('[') &&
                !rawBody.trimStart().startsWith('<')
            fun expandJson(element: JsonElement): JsonElement = when (element) {
                is JsonObject -> JsonObject(element.mapValues { expandJson(it.value) })
                is JsonArray -> JsonArray(element.map(::expandJson))
                is JsonPrimitive -> if (element.isString) JsonPrimitive(expand(element.content, false)) else element
            }
            val body = rawBody?.let { raw -> when {
                isForm -> raw.split('&').joinToString("&") { field -> field.split('=', limit = 2)
                    .joinToString("=") {
                        val expanded = expand(it, true)
                        if (options["charset"] == null && encodedForm.matches(expanded)) expanded
                        else encode(expand(it, false), charset)
                    } }
                raw.trimStart().startsWith('{') || raw.trimStart().startsWith('[') ->
                    expandJson(RequestOptionsJson.parse(raw)).toString()
                else -> expand(raw, false)
            } }
            if (body != null && method in setOf("GET", "HEAD")) return CompiledRequest.Rejected(FailureCode.InvalidRequest)
            if (body != null && contentType == null) mergedHeaders["Content-Type"] = when {
                isForm -> "application/x-www-form-urlencoded"
                body.trimStart().startsWith('<') -> "application/xml"
                else -> "application/json"
            } + "; charset=${if (charset == "escape") "UTF-8" else charset}"
            val retry = options["retry"]?.jsonPrimitive?.int ?: 0
            if (retry !in 0..3) return CompiledRequest.Rejected(FailureCode.InvalidRequest)
            CompiledRequest.Ready(BrokerRequest(id, url, method, mergedHeaders.toMap(), body,
                if (charset == "escape") "UTF-8" else charset, retry = retry, kind = kind,
                browser = browser.takeUnless { responseAsHex }, responseAsHex = responseAsHex))
        } catch (failure: BrokerFailure) { CompiledRequest.Rejected(failure.code) }
          catch (_: Exception) { CompiledRequest.Rejected(FailureCode.InvalidRequest) }
    }

    // AnalyzeUrl.appendEncoded preserves complete percent-encoded form values when no
    // charset is specified. Template keywords are encoded as data before this check.
    private val encodedForm = Regex("(?:[a-zA-Z0-9*._-]|%[0-9a-fA-F]{2})*+")

    private fun encode(value: String, charset: String): String = if (charset == "escape") buildString {
        value.forEach { char -> when {
            char.isLetterOrDigit() && char.code < 128 || char in "@*_+-./" -> append(char)
            char.code < 256 -> append("%%%02X".format(char.code))
            else -> append("%%u%04X".format(char.code))
        } }
    } else URLEncoder.encode(value, charset)

    private fun encodeNonAscii(value: String, charset: String): String = Regex("[^\\x21-\\x7e]+").replace(value) { encode(it.value, charset) }
}
