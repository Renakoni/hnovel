package hnovel.rhino

import org.mozilla.javascript.ScriptableObject

/** Worker-owned live JS state. Never serialized or shared between source/profile identities. */
class ScriptLibrary(val sourceId: String, val profile: String, scripts: List<String>) : AutoCloseable {
    constructor(sourceId: String, profile: String, code: String) : this(sourceId, profile, listOf(code))
    internal val scripts = scripts.toList()
    internal var scope: ScriptableObject? = null
    internal var closed = false

    @Synchronized override fun close() {
        closed = true
        scope = null
    }
}
