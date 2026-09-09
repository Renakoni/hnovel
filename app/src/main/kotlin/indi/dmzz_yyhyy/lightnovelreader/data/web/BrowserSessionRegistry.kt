package indi.dmzz_yyhyy.lightnovelreader.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class BrowserCallbackResult { Accepted, Stale, Cancelled }

data class BrowserSession(val source: Identifier, val epoch: Long, val profileName: String)

/** Tracks browser epochs; WebView instances must be discarded when an epoch ends. */
class BrowserSessionRegistry {
    private val epochs = ConcurrentHashMap<Identifier, Long>()

    @Synchronized fun open(source: Identifier): BrowserSession {
        val epoch = (epochs[source] ?: 0) + 1
        epochs[source] = epoch
        return BrowserSession(source, epoch, profileName(source))
    }

    fun accepts(session: BrowserSession): Boolean = epochs[session.source] == session.epoch

    @Synchronized fun cancel(session: BrowserSession) {
        if (accepts(session)) epochs[session.source] = session.epoch + 1
    }

    fun callback(session: BrowserSession, cancelled: Boolean = false): BrowserCallbackResult = when {
        cancelled -> BrowserCallbackResult.Cancelled
        !accepts(session) -> BrowserCallbackResult.Stale
        else -> BrowserCallbackResult.Accepted
    }

    private fun profileName(source: Identifier): String {
        val input = "${source.namespace.length}:${source.namespace}${source.id.length}:${source.id}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return "source-" + digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
