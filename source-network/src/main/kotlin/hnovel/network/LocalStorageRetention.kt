package hnovel.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Source-declared general preferences only. This declaration never grants origin access. */
@Serializable
data class LocalStorageRetention(val origins: Map<String, List<String>> = emptyMap()) {
    init {
        require(origins.size <= MAX_ORIGINS && origins.values.sumOf { it.size } <= MAX_KEYS)
        origins.forEach { (origin, keys) ->
            require(sourceOrigin(origin) == origin && canonicalOrigin(origin) == origin)
            require(keys.distinct().size == keys.size)
            require(keys.all { it.isNotEmpty() && it.length <= MAX_KEY_CHARS && it.none(Char::isISOControl) })
        }
    }

    fun approved(grants: List<NetworkGrant>): LocalStorageRetention {
        val allowed = grants.mapNotNull { sourceOrigin(it.origin) }.toSet()
        return LocalStorageRetention(origins.filterKeys { it in allowed }.mapValues { it.value.toList() })
    }

    fun select(values: Map<String, Map<String, String>>): Map<String, Map<String, String>> =
        origins.mapValues { (origin, keys) -> values[origin].orEmpty().filterKeys { it in keys } }
            .filterValues { it.isNotEmpty() }.also(::validate)

    fun validate(values: Map<String, Map<String, String>>) {
        var size = 0L
        values.forEach { (origin, entries) ->
            val keys = requireNotNull(origins[origin])
            require(entries.keys.all { it in keys })
            size += origin.length
            entries.forEach { (key, value) ->
                require(value.length <= MAX_VALUE_CHARS)
                size += key.length + value.length
            }
        }
        require(size <= MAX_TOTAL_CHARS)
    }

    companion object {
        const val FIELD = "preserveLocalStorage"
        const val MAX_ORIGINS = 8
        const val MAX_KEYS = 64
        const val MAX_KEY_CHARS = 128
        const val MAX_VALUE_CHARS = 16 * 1024
        const val MAX_TOTAL_CHARS = 64 * 1024

        fun parse(value: JsonElement?): LocalStorageRetention {
            if (value == null || value == JsonNull) return LocalStorageRetention()
            require(value is JsonObject)
            val origins = linkedMapOf<String, List<String>>()
            value.forEach { (address, entries) ->
                val origin = canonicalOrigin(address)
                require(origin !in origins && entries is JsonArray)
                origins[origin] = entries.map { key ->
                    require(key is JsonPrimitive && key.isString)
                    key.content
                }
            }
            return LocalStorageRetention(origins)
        }

        private fun canonicalOrigin(address: String): String {
            val url = requireNotNull(address.toHttpUrlOrNull())
            require(url.encodedPath == "/" && url.query == null && url.fragment == null && '*' !in url.host)
            return requireNotNull(sourceOrigin(address))
        }
    }
}
