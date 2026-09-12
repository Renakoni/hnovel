package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import hnovel.content.LoginForm
import hnovel.content.SourceContentException
import hnovel.content.ContentError
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionManager
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

data class LoginAttempt internal constructor(val source: Identifier, val generation: Long, val revision: String)
enum class LoginStatus { LoggedOut, Authenticated, Required }

/** No Activity is launched from a rule/worker. Foreground UI explicitly owns a cancellable login attempt. */
@Singleton
class SourceLoginService @Inject constructor(private val sources: ImportedRuleSources, private val accounts: SourceSessionManager) {
    // loginForm() may suspend across logout, revision change or removal. Validate the same attempt
    // before loading and before returning, rejecting any form produced by a retired attempt.
    suspend fun form(attempt: LoginAttempt): LoginForm = target(attempt).rules.loginForm().also { target(attempt) }
    suspend fun status(source: Identifier): LoginStatus = withContext(Dispatchers.IO) {
        val target = sources.loginTarget(source)
        val status = (target.session.read(StorageRequest(StorageArea.Account, "login/status")) as? StorageResult.Value)?.value
        when (status) { "authenticated" -> LoginStatus.Authenticated; "required" -> LoginStatus.Required; else -> LoginStatus.LoggedOut }
    }
    suspend fun begin(source: Identifier): LoginAttempt {
        val target = sources.rotateAccount(source)
        return LoginAttempt(source, target.generation, target.revision)
    }
    suspend fun submit(attempt: LoginAttempt, values: Map<String, String>, action: String? = null) {
        val target = target(attempt)
        try {
            target.rules.login(values.toMap(), action)
            target(attempt)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancel(attempt) }
            throw cancelled
        } catch (failure: Exception) {
            // A transport/grant failure does not establish that the credentials were rejected.
            if (failure is SourceContentException && failure.code == ContentError.LoginRequired &&
                accounts.current(attempt.source).generation == attempt.generation) {
                target.session.write(StorageRequest(StorageArea.Account, "login/status", "required"))
            }
            throw failure
        }
    }
    suspend fun cancel(attempt: LoginAttempt) {
        if (accounts.current(attempt.source).generation == attempt.generation)
            runCatching { sources.rotateAccount(attempt.source, attempt.generation) }
    }
    suspend fun logout(source: Identifier) { sources.rotateAccount(source) }

    private suspend fun target(attempt: LoginAttempt): RuleLoginTarget {
        val current = sources.loginTarget(attempt.source)
        if (current.generation != attempt.generation || current.revision != attempt.revision ||
            accounts.current(attempt.source).generation != attempt.generation)
            throw SourceContentException(ContentError.Unavailable, "login")
        return current
    }
}
