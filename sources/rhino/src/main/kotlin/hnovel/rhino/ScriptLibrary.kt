package hnovel.rhino

import org.mozilla.javascript.ScriptableObject

/** Worker-owned live JS state. Never serialized or shared between source/profile identities. */
class ScriptLibrary(val sourceId: String, val profile: String, scripts: List<String>) : AutoCloseable {
    constructor(sourceId: String, profile: String, code: String) : this(sourceId, profile, listOf(code))
    internal val scripts = scripts.toList()
    internal var scope: ScriptableObject? = null
    internal var realm: ScriptRealm? = null
    internal var closed = false

    /** A size failure may happen after a native mutation; no failed realm is reused. */
    @Synchronized internal fun discardState() {
        scope = null
        realm = null
    }

    @Synchronized override fun close() {
        closed = true
        discardState()
    }
}
