package hnovel.execution

import kotlinx.serialization.json.*

/** The pinned jsLib object is an ordered name-to-URL map, not a collection of inline snippets. */
object SourceLibraryDefinition {
    const val MAX_CHARS = 256 * 1024

    fun isUrlMap(definition: String?): Boolean = definition?.trim()?.let { it.startsWith('{') && it.endsWith('}') } == true

    fun urls(definition: String): List<String> {
        require(isUrlMap(definition)) { "Library URL map required" }
        val map = BridgeWire.arguments("[$definition]".toByteArray(Charsets.UTF_8)).single().jsonObject
        return map.values.map { value ->
            require(value is JsonPrimitive && value.isString) { "Library URL must be a string" }
            value.content
        }.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }
}

class LibraryTooLarge : RuntimeException("Library exceeds input budget")
