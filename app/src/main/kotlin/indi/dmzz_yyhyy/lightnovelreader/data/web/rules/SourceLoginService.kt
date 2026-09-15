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

data class LoginAttempt internal constructor(val source: Identifier, val generation: Long, val revision: String,
    val retireOnCancel: Boolean = true)
/** Saved facts only. Submitting a login script does not confirm server-side authentication. */
enum class LoginStatus { LoggedOut, LoginSubmitted, SessionSaved, Required }

/** No Activity is launched from a rule/worker. Foreground UI explicitly owns a cancellable login attempt. */
@Singleton
class SourceLoginService @Inject constructor(private val sources: ImportedRuleSources, private val accounts: SourceSessionManager) {
    // loginForm() may suspend across logout, revision change or removal. Validate the same attempt
    // before loading and before returning, rejecting any form produced by a retired attempt.
    suspend fun form(attempt: LoginAttempt): LoginForm = target(attempt).rules.loginForm().also { target(attempt) }
    suspend fun status(source: Identifier): LoginStatus = withContext(Dispatchers.IO) {
        val target = sources.loginTarget(source)
        savedStatus((target.session.read(StorageRequest(StorageArea.Account, "login/status")) as? StorageResult.Value)?.value)
    }
    suspend fun begin(source: Identifier): LoginAttempt {
        val current = sources.loginTarget(source)
        // An outstanding verification resumes its account. A fresh login resets the old account
        // before showing the page, including native sites that store tokens outside Cookie.
        val native = current.session.browserRead
        val pending = if (native)
            (current.session.read(StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL)) as? StorageResult.Value
                ?: error("Stored browser state is unavailable")).value else null
        val keepSession = !pending.isNullOrBlank()
        val target = if (keepSession) current else sources.rotateAccount(source)
        // Closing a native window still keeps its current (possibly partial) website session.
        return LoginAttempt(source, target.generation, target.revision, retireOnCancel = !native)
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
        if (attempt.retireOnCancel && accounts.current(attempt.source).generation == attempt.generation)
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
    internal companion object {
        // Conservative display convention, not a new login schema. Ambiguous forms have no label.
        private val accountNames = setOf("user", "username", "account", "email", "账号", "帐号", "账户", "用户名", "邮箱")
        fun accountNameField(form: LoginForm?): String? = form?.fields?.filter {
            it.type == "text" && it.name.trim().lowercase(java.util.Locale.ROOT) in accountNames
        }?.singleOrNull()?.name

        fun savedAccountName(field: String, info: String?): String? {
            if (field.trim().lowercase(java.util.Locale.ROOT) !in accountNames || info == null) return null
            val value = runCatching { (Json.parseToJsonElement(info) as? JsonObject)?.get(field) as? JsonPrimitive }.getOrNull()
            return value?.takeIf { it.isString }?.content?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl)
            }
        }
        fun savedStatus(value: String?) = when (value) {
            "authenticated" -> LoginStatus.LoginSubmitted // Keep the existing persisted value compatible.
            "session" -> LoginStatus.SessionSaved
            "required" -> LoginStatus.Required
            else -> LoginStatus.LoggedOut
        }
    }
}
