package hnovel.network

import kotlinx.serialization.Serializable

/** The host installs this capability. URL rules cannot select a session or request foreground UI. */
fun interface BrowserExecutor {
    suspend fun defaultUserAgent(): String? = null

    /** Optional UI feedback; headless hosts may omit display without changing script results. */
    suspend fun showMessage(message: String, long: Boolean, guard: RequestCommitGuard) { guard.commit {} }

    suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
        guard: RequestCommitGuard): BrokerResult
    fun clearAccount(scope: SourceScope) {}
}

@Serializable data class BrowserOptions(val script: String = "", val delayMillis: Long = 0,
    val sourceRegex: String = "", val html: String? = null, val interactive: Boolean = false, val title: String = "",
    val overrideUrl: Boolean = false, val verificationCode: Boolean = false)
