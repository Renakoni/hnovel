package hnovel.content

import hnovel.imports.SourceDefinition
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest

/** Parsed definition data. Nothing here grants network access or executes source expressions. */
internal class RuleSourceDefinition(val stored: SourceDefinition) {
    private val root = Json.parseToJsonElement(stored.rawJson).jsonObject
    val baseUrl = root.string("bookSourceUrl")
    val library = root.string("jsLib").takeIf { it.isNotBlank() }
    val searchUrl = root.string("searchUrl")
    val exploreUrl = root.string("exploreUrl")
    val exploreScreen = root.string("exploreScreen")
    val customButton = root["customButton"]?.jsonPrimitive?.booleanOrNull ?: false
    val eventListener = root["eventListener"]?.jsonPrimitive?.booleanOrNull ?: false
    val bookUrlPattern = root.string("bookUrlPattern")
    val header = root.string("header")
    val loginCheck = root.string("loginCheckJs")
    val loginUrl = root.string("loginUrl")
    val loginUi = root.string("loginUi")
    val cookiesEnabled = root["enabledCookieJar"]?.jsonPrimitive?.booleanOrNull ?: true
    val coverDecode = root.string("coverDecodeJs")
    val search = rules("ruleSearch")
    val explore = rules("ruleExplore").takeIf { it.isNotEmpty() } ?: search
    val information = rules("ruleBookInfo")
    val toc = rules("ruleToc")
    val content = rules("ruleContent")
    private fun rules(name: String): JsonObject = when (val value = root[name]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> value
        is JsonPrimitive -> Json.parseToJsonElement(value.content).jsonObject
        else -> throw SourceContentException(ContentError.InvalidRule, name)
    }
}

internal fun JsonObject.string(name: String) = when (val value = this[name]) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}
internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Keep request options with their logical URL; their scripts are evaluated only before dispatch. */
internal fun sourceLink(base: String, value: String): String {
    val option = Regex(",\\s*(?=\\{)").find(value)?.range?.first ?: value.length
    val address = value.substring(0, option).trim()
    val baseEnd = Regex(",\\s*(?=\\{)").find(base)?.range?.first ?: base.length
    val resolved = try { URI(base.substring(0, baseEnd).trim()).resolve(address) }
        catch (_: Exception) { throw SourceContentException(ContentError.InvalidRule, "url") }
    val scheme = resolved.scheme?.lowercase()
    if (scheme !in setOf("http", "https") || resolved.host.isNullOrBlank() || resolved.userInfo != null)
        throw SourceContentException(ContentError.InvalidRule, "url")
    return resolved.toString().replaceFirst(Regex("^[A-Za-z][A-Za-z0-9+.-]*:"), "$scheme:") + value.substring(option)
}
