package hnovel.network

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.nio.charset.Charset

/** Static Legado URL/options. Expressions requiring JS/browser return an explicit deferred requirement. */
class RequestCompiler {
    fun compile(id: String, rule: String, baseUrl: String, keyword: String = "", page: Int = 1,
        headers: Map<String, String> = emptyMap(), kind: ResourceKind = ResourceKind.Document): CompiledRequest {
        if (rule.length > 65536 || keyword.length > 65536 || page < 1) return CompiledRequest.Rejected(FailureCode.InvalidRequest)
        return try {
            if (rule.contains("<js>", true) || rule.contains("@js:", true)) return CompiledRequest.Rejected(FailureCode.ScriptRequired)
            if (Regex("\\{\\{(.*?)}}").findAll(rule).any { it.groupValues[1].trim() !in setOf("key", "page", "baseUrl") }) {
                return CompiledRequest.Rejected(FailureCode.ScriptRequired)
            }
            val optionStart = Regex(",\\s*(?=\\{)").find(rule)
            val options = optionStart?.let { Json.parseToJsonElement(rule.substring(it.range.last + 1)).jsonObject } ?: buildJsonObject {}
            if (options.keys.any { it in setOf("js") }) return CompiledRequest.Rejected(FailureCode.ScriptRequired)
            if (options.keys.any { it in setOf("webView", "webJs", "webViewDelayTime", "serverID") }) return CompiledRequest.Rejected(FailureCode.BrowserRequired)
            if (options.keys.any { it !in setOf("method", "body", "headers", "header", "charset", "retry") }) return CompiledRequest.Rejected(FailureCode.UnknownOption)
            val charset = options["charset"]?.jsonPrimitive?.content ?: "UTF-8"
            if (charset != "escape") Charset.forName(charset)
            fun expand(value: String, encodeKey: Boolean, pageAlternatives: Boolean = false): String {
                var text = Regex("\\{\\{\\s*(.*?)\\s*}}").replace(value) { match -> when (match.groupValues[1].trim()) {
                    "key" -> if (encodeKey) encode(keyword, charset) else keyword
                    "page" -> page.toString()
                    "baseUrl" -> baseUrl
                    else -> throw BrokerFailure(RequestStage.Parse, FailureCode.ScriptRequired)
                } }
                if ("{{" in text) throw BrokerFailure(RequestStage.Parse, FailureCode.ScriptRequired)
                if (pageAlternatives) text = Regex("<([^<>]+)>").replace(text) { match ->
                    val pages = match.groupValues[1].split(',')
                    pages[(page - 1).coerceAtMost(pages.lastIndex)].trim()
                }
                return text
            }
            val rawUrl = rule.substring(0, optionStart?.range?.first ?: rule.length).trim()
            val expandedUrl = expand(rawUrl, true, pageAlternatives = true)
            val url = baseUrl.toHttpUrlOrNull()?.resolve(encodeNonAscii(expandedUrl, charset))
                ?: encodeNonAscii(expandedUrl, charset).toHttpUrlOrNull()
                ?: return CompiledRequest.Rejected(FailureCode.InvalidRequest)
            val method = options["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
            if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) return CompiledRequest.Rejected(FailureCode.InvalidRequest)
            val mergedHeaders = headers.toMutableMap()
            val optionHeaders = options["headers"] ?: options["header"]
            val parsedHeaders = if (optionHeaders is JsonPrimitive) Json.parseToJsonElement(optionHeaders.content).jsonObject else optionHeaders?.jsonObject
            parsedHeaders?.forEach { (key, value) ->
                mergedHeaders.keys.removeAll { it.equals(key, true) }
                mergedHeaders[key] = expand(value.jsonPrimitive.content, false)
            }
            val rawBody = options["body"]?.let { if (it is JsonPrimitive) it.content else it.toString() }
            val contentType = mergedHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            val isForm = rawBody != null && contentType == null && !rawBody.trimStart().startsWith('{') &&
                !rawBody.trimStart().startsWith('[') && !rawBody.trimStart().startsWith('<')
            fun expandJson(element: JsonElement): JsonElement = when (element) {
                is JsonObject -> JsonObject(element.mapValues { expandJson(it.value) })
                is JsonArray -> JsonArray(element.map(::expandJson))
                is JsonPrimitive -> if (element.isString) JsonPrimitive(expand(element.content, false)) else element
            }
            val body = rawBody?.let { raw -> when {
                isForm -> raw.split('&').joinToString("&") { field -> field.split('=', limit = 2)
                    .joinToString("=") { encode(expand(it, false), charset) } }
                raw.trimStart().startsWith('{') || raw.trimStart().startsWith('[') ->
                    expandJson(Json.parseToJsonElement(raw)).toString()
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
            CompiledRequest.Ready(BrokerRequest(id, url.toString(), method, mergedHeaders.toMap(), body,
                if (charset == "escape") "UTF-8" else charset, retry = retry, kind = kind))
        } catch (failure: BrokerFailure) { CompiledRequest.Rejected(failure.code) }
          catch (_: Exception) { CompiledRequest.Rejected(FailureCode.InvalidRequest) }
    }

    private fun encode(value: String, charset: String): String = if (charset == "escape") buildString {
        value.forEach { char -> when {
            char.isLetterOrDigit() && char.code < 128 || char in "@*_+-./" -> append(char)
            char.code < 256 -> append("%%%02X".format(char.code))
            else -> append("%%u%04X".format(char.code))
        } }
    } else URLEncoder.encode(value, charset)

    private fun encodeNonAscii(value: String, charset: String): String = Regex("[^\\x21-\\x7e]+").replace(value) { encode(it.value, charset) }
}
