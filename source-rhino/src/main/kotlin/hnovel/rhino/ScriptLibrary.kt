package hnovel.rhino

import org.mozilla.javascript.ScriptableObject

/** Worker-owned live JS state. Never serialized or shared between source/profile identities. */
class ScriptLibrary(val sourceId: String, val profile: String, internal val code: String) : AutoCloseable {
    internal var scope: ScriptableObject? = null
    internal var closed = false

    @Synchronized override fun close() {
        closed = true
        scope = null
    }
}
