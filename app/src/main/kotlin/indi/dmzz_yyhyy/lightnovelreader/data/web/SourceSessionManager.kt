package indi.dmzz_yyhyy.lightnovelreader.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Owns login state and cookies per source. Credentials never leave the caller that performs login. */
@Singleton
class SourceSessionManager @Inject constructor(private val executionAuthority: hnovel.execution.ExecutionAuthority = hnovel.execution.ExecutionAuthority()) {
    private val sessions = ConcurrentHashMap<Identifier, State>()

    @Synchronized fun begin(source: Identifier): SourceSession {
        executionAuthority.revokeSource(source.id, source.namespace)
        val next = State(generation = (sessions[source]?.generation ?: 0) + 1)
        sessions[source] = next
        return snapshot(source, next)
    }

    @Synchronized fun current(source: Identifier): SourceSession = snapshot(source, sessions[source] ?: State(active = false))

    @Synchronized fun setCookies(session: SourceSession, cookies: Map<String, String>): Boolean {
        val state = sessions[session.source] ?: return false
        if (state.generation != session.generation || state.nonce != session.nonce || !state.active) return false
        state.cookies = cookies.toMap()
        return true
    }

    @Synchronized fun logout(session: SourceSession): SourceSession {
        val state = sessions[session.source]
        if (state == null || state.generation != session.generation || state.nonce != session.nonce)
            return SourceSession(session.source, session.generation, false, emptyMap(), session.nonce)
        executionAuthority.revokeSource(session.source.id, session.source.namespace)
        val next = State(generation = state.generation + 1)
        sessions[session.source] = next
        return snapshot(session.source, next)
    }

    fun accepts(session: SourceSession): Boolean = sessions[session.source]?.let {
        it.active && it.generation == session.generation && it.nonce == session.nonce
    } == true

    fun redact(text: String): String = text
        .replace(Regex("(?i)(password|token|cookie|authorization)=[^&\\s]+"), "$1=<redacted>")

    private fun snapshot(source: Identifier, state: State) = SourceSession(source, state.generation, state.active,
        state.cookies.toMap(), state.nonce)

    private data class State(val generation: Long = 0, var active: Boolean = true,
        var cookies: Map<String, String> = emptyMap(), val nonce: String = UUID.randomUUID().toString())
}

data class SourceSession internal constructor(
    val source: Identifier,
    val generation: Long,
    val active: Boolean,
    val cookies: Map<String, String>,
    internal val nonce: String,
)
