package hnovel.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class SourceCookies(private val storage: SourceStorage, private val invalidateCache: () -> Unit) {
    @Serializable private data class SavedCookie(val origin: String, val cookie: String)
    private val cookies = linkedMapOf<String, Pair<String, Cookie>>()

    init {
        val stored = storage.read("cookies")
        if (stored is StorageResult.Failure) throw BrokerFailure(RequestStage.Storage, stored.code)
        val saved = (stored as StorageResult.Value).value
        val decoded = if (saved == null) emptyList() else try { Json.decodeFromString<List<SavedCookie>>(saved) }
            catch (_: Exception) { throw BrokerFailure(RequestStage.Storage, FailureCode.StorageUnavailable) }
        decoded.forEach {
            val cookie = Cookie.parse(it.origin.toHttpUrl(), it.cookie)
            if (cookie != null && cookie.expiresAt > System.currentTimeMillis()) cookies[key(cookie)] = it.origin to cookie
        }
    }

    @Synchronized fun header(url: HttpUrl, explicit: String?): String {
        if (cookies.entries.removeAll { it.value.second.expiresAt <= System.currentTimeMillis() }) invalidateCache()
        val values = linkedMapOf<String, String>()
        cookies.values.map { it.second }.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            .sortedBy { it.path.length }.forEach { values[it.name] = it.value }
        // Explicit rule/account cookies override same-name jar cookies only for this destination.
        explicit?.split(';')?.forEach { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2) values[pair[0]] = pair[1]
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    @Synchronized fun save(url: HttpUrl, headers: okhttp3.Headers) {
        val incoming = Cookie.parseAll(url, headers)
        if (incoming.isEmpty()) return
        val next = LinkedHashMap(cookies)
        incoming.forEach { cookie ->
            if (cookie.expiresAt <= System.currentTimeMillis()) next.remove(key(cookie)) else next[key(cookie)] =
                url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString() to cookie
        }
        next.entries.removeAll { it.value.second.expiresAt <= System.currentTimeMillis() }
        if (next.size > 256) throw BrokerFailure(RequestStage.Storage, FailureCode.StorageQuota)
        // Session cookies are intentionally memory-only; persistent cookies retain absolute expiry.
        val saved = Json.encodeToString(next.values.filter { it.second.persistent }.map { SavedCookie(it.first, it.second.toString()) })
        when (val result = storage.write("cookies", saved)) {
            is StorageResult.Failure -> throw BrokerFailure(RequestStage.Storage, result.code)
            is StorageResult.Value -> { cookies.clear(); cookies.putAll(next); invalidateCache() }
        }
    }

    private fun key(cookie: Cookie) = "${cookie.name}\n${cookie.domain}\n${cookie.path}"
}
