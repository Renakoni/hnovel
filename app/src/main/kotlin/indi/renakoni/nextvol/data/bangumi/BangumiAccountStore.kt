package indi.renakoni.nextvol.data.bangumi

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.network.StorageCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** No data-class toString: credentials must never become an error or UI state. */
class BangumiSession internal constructor(val user: BangumiUser, val generation: String, private val token: String) {
    private var valid = true
    private val calls = mutableSetOf<Call>()

    @Synchronized internal fun request(builder: Request.Builder, create: (Request) -> Call): Call {
        checkActive()
        return create(builder.header("Authorization", "Bearer $token").build()).also(calls::add)
    }
    @Synchronized internal fun finished(call: Call) { calls.remove(call) }
    @Synchronized fun checkActive() { if (!valid) throw CancellationException("Bangumi account disconnected") }
    @Synchronized internal fun revoke() {
        valid = false
        calls.forEach(Call::cancel)
        calls.clear()
    }
}

data class BangumiAccountState(val user: BangumiUser? = null, val loaded: Boolean = false, val unreadable: Boolean = false)

@Singleton
class BangumiAccountStore @Inject constructor(@ApplicationContext context: Context, private val cipher: StorageCipher) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "bangumi-account.enc"))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(BangumiAccountState())
    val state = mutableState.asStateFlow()
    @Volatile private var current: BangumiSession? = null

    @Serializable private class SavedAccount(val user: BangumiUser, val generation: String, val token: String)

    suspend fun session(): BangumiSession? {
        load()
        return current
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (state.value.loaded) return@withLock
            try {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                    val bytes = file.openRead().use { input ->
                        val result = input.readBytes()
                        require(result.size <= 32 * 1024)
                        result
                    }
                    val saved = bangumiJson.decodeFromString<SavedAccount>(cipher.open(bytes, IDENTITY).toString(Charsets.UTF_8))
                    validateToken(saved.token)
                    current = BangumiSession(saved.user, saved.generation, saved.token)
                }
                mutableState.value = BangumiAccountState(current?.user, loaded = true)
            } catch (_: Exception) {
                mutableState.value = BangumiAccountState(loaded = true, unreadable = true)
            }
        }
    }

    suspend fun connect(user: BangumiUser, token: String): BangumiSession = withContext(Dispatchers.IO) {
        validateToken(token)
        mutex.withLock {
            val saved = SavedAccount(user, UUID.randomUUID().toString(), token)
            write(saved)
            current?.revoke()
            BangumiSession(user, saved.generation, token).also {
                current = it
                mutableState.value = BangumiAccountState(user, loaded = true)
            }
        }
    }

    suspend fun updateProfile(session: BangumiSession, user: BangumiUser) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (current !== session) return@withLock
            session.checkActive()
            require(user.id == session.user.id)
            val saved = file.openRead().use { input ->
                bangumiJson.decodeFromString<SavedAccount>(cipher.open(input.readBytes(), IDENTITY).toString(Charsets.UTF_8))
            }
            write(SavedAccount(user, saved.generation, saved.token))
            mutableState.value = BangumiAccountState(user, loaded = true)
        }
    }

    private fun write(saved: SavedAccount) {
        val bytes = cipher.seal(bangumiJson.encodeToString(saved).toByteArray(Charsets.UTF_8), IDENTITY)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            current?.revoke()
            current = null
            file.delete()
            mutableState.value = BangumiAccountState(loaded = true)
        }
    }

    companion object {
        private const val IDENTITY = "bangumi-account-v1"
        internal fun validateToken(token: String) {
            require(token.isNotBlank() && token.length <= 4096 && token.all { it.code in 33..126 })
        }
    }
}
