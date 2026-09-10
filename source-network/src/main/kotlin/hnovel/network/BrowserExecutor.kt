package hnovel.network

import kotlinx.serialization.Serializable

/** The host installs this capability. URL rules cannot select a session or request foreground UI. */
fun interface BrowserExecutor {
    suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
        guard: RequestCommitGuard): BrokerResult
}

@Serializable data class BrowserOptions(val script: String = "", val delayMillis: Long = 0,
    val sourceRegex: String = "", val html: String? = null, val interactive: Boolean = false,
    val overrideUrl: Boolean = false)
