package hnovel.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class SourceCookies(private val storage: SourceStorage) {
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
        cookies.entries.removeAll { it.value.second.expiresAt <= System.currentTimeMillis() }
        val matching = cookies.values.map { it.second }
            .filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            .sortedByDescending { it.path.length }
            .toMutableList()
        // Explicit rule/account cookies override every same-name jar cookie for this destination.
        val explicitValues = linkedMapOf<String, String>()
        explicit?.split(';')?.forEach { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2) explicitValues[pair[0]] = pair[1]
        }
        if (explicitValues.isNotEmpty()) {
            matching.removeAll { it.name in explicitValues }
            return (explicitValues.map { "${it.key}=${it.value}" } + matching.map { "${it.name}=${it.value}" })
                .joinToString("; ")
        }
        return matching.joinToString("; ") { "${it.name}=${it.value}" }
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
            is StorageResult.Value -> { cookies.clear(); cookies.putAll(next) }
        }
    }

    private fun key(cookie: Cookie) = "${cookie.name}\n${cookie.domain}\n${cookie.path}"

    @Synchronized fun setHeader(url: HttpUrl, value: String, replace: Boolean) {
        require(value.length <= 65536)
        val before = snapshot()
        if (replace) cookies.entries.removeAll { it.value.second.matches(url) }
        val headers = okhttp3.Headers.Builder()
        value.split(';').map { it.trim() }.filter { '=' in it }.forEach { pair ->
            headers.add("Set-Cookie", "$pair; Path=/; Max-Age=31536000")
        }
        try {
            if (value.isBlank()) {
                val saved = Json.encodeToString(cookies.values.filter { it.second.persistent }.map { SavedCookie(it.first, it.second.toString()) })
                check(storage.write("cookies", saved) is StorageResult.Value)
            } else save(url, headers.build())
        } catch (failure: Exception) { restoreMemory(before); throw failure }
    }

    @Synchronized fun snapshot(): List<Pair<String, Cookie>> = cookies.values.toList()
    @Synchronized fun restoreMemory(snapshot: List<Pair<String, Cookie>>) {
        cookies.clear()
        snapshot.forEach { cookies[key(it.second)] = it }
    }
}

