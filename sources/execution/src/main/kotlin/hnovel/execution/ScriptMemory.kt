package hnovel.execution

/** Host-owned text values for `cache.*Memory`. Never persisted and never shared across owners. */
class ScriptMemory(private val maxEntries: Int = 64, private val maxChars: Int = 64 * 1024) {
    private val values = LinkedHashMap<String, String>()
    private var chars = 0

    @Synchronized fun get(key: String): String? = values[key]

    /** Rejects a write that would exceed the owner's bounds; existing values stay unchanged. */
    @Synchronized fun put(key: String, value: String) {
        require(key.length <= 256) { "Memory key too long" }
        val previous = values[key]
        val next = chars - (previous?.let { key.length + it.length } ?: 0) + key.length + value.length
        require(next <= maxChars && (previous != null || values.size < maxEntries)) { "Memory cache limit exceeded" }
        values[key] = value
        chars = next
    }

    @Synchronized fun delete(key: String) {
        values.remove(key)?.let { chars -= key.length + it.length }
    }
}
