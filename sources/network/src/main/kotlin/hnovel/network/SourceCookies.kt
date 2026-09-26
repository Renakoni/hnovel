package hnovel.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class SourceCookies(private val storage: SourceStorage) {
    @Serializable private data class SavedCookie(val origin: String, val cookie: String, val browserOwned: Boolean = false)
    private val cookies = linkedMapOf<String, Pair<String, Cookie>>()
    private val browserOnly = mutableSetOf<String>()
    private var seedVersion = 0L
    @Synchronized fun browserSeed(url: HttpUrl) = NativeBrowserCookieSeed(seedVersion, browserSnapshot(url))

    init {
        val stored = storage.read("cookies")
        if (stored is StorageResult.Failure) throw BrokerFailure(RequestStage.Storage, stored.code)
        val saved = (stored as StorageResult.Value).value
        val decoded = if (saved == null) emptyList() else try { Json.decodeFromString<List<SavedCookie>>(saved) }
            catch (_: Exception) { throw BrokerFailure(RequestStage.Storage, FailureCode.StorageUnavailable) }
        decoded.forEach {
            val cookie = Cookie.parse(it.origin.toHttpUrl(), it.cookie)
            if (cookie != null && cookie.expiresAt > System.currentTimeMillis()) {
                cookies[key(cookie)] = it.origin to cookie
                if (it.browserOwned) browserOnly.add(key(cookie))
            }
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

    @Synchronized fun save(url: HttpUrl, headers: okhttp3.Headers, fromBrowser: Boolean = false) {
        val incoming = Cookie.parseAll(url, headers)
        if (incoming.isEmpty()) return
        val next = LinkedHashMap(cookies)
        incoming.forEach { cookie ->
            if (cookie.expiresAt <= System.currentTimeMillis()) next.remove(key(cookie)) else next[key(cookie)] =
                url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString() to cookie
        }
        next.entries.removeAll { it.value.second.expiresAt <= System.currentTimeMillis() }
        if (next.size > 256) throw BrokerFailure(RequestStage.Storage, FailureCode.StorageQuota)
        val nextBrowserOnly = browserOnly.intersect(next.keys).toMutableSet()
        if (fromBrowser) nextBrowserOnly.addAll(incoming.map(::key)) else nextBrowserOnly.removeAll(incoming.map(::key).toSet())
        // Session cookies are intentionally memory-only; persistent cookies retain absolute expiry.
        val saved = encode(next.values, nextBrowserOnly)
        when (val result = storage.write("cookies", saved)) {
            is StorageResult.Failure -> throw BrokerFailure(RequestStage.Storage, result.code)
            is StorageResult.Value -> {
                cookies.clear(); cookies.putAll(next)
                browserOnly.clear(); browserOnly.addAll(nextBrowserOnly)
                if (!fromBrowser) seedVersion++
            }
        }
    }

    private fun key(cookie: Cookie) = "${cookie.name}\n${cookie.domain}\n${cookie.path}"

    private fun encode(values: Collection<Pair<String, Cookie>>, owned: Set<String> = browserOnly) =
        Json.encodeToString(values.filter { it.second.persistent }.map {
            SavedCookie(it.first, it.second.toString(), key(it.second) in owned)
        })

    @Synchronized fun documentHeader(url: HttpUrl): String = cookies.values.map { it.second }
        .filter { !it.httpOnly && it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        .sortedByDescending { it.path.length }.joinToString("; ") { "${it.name}=${it.value}" }

    @Synchronized fun documentCookie(url: HttpUrl, value: String) {
        require(value.length <= 8192)
        val parsed = Cookie.parse(url, value) ?: return
        require(!parsed.httpOnly && cookies[key(parsed)]?.second?.httpOnly != true)
        save(url, okhttp3.Headers.Builder().add("Set-Cookie", value).build())
    }

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
                val saved = encode(cookies.values)
                check(storage.write("cookies", saved) is StorageResult.Value)
                seedVersion++
            } else save(url, headers.build())
        } catch (failure: Exception) { restoreMemory(before); throw failure }
    }

    @Synchronized fun snapshot(): List<Pair<String, Cookie>> = cookies.values.toList()

    @Synchronized fun browserSnapshot(url: HttpUrl): List<String> = cookies.values.map { it.second }
        .filter { key(it) !in browserOnly && it.expiresAt > System.currentTimeMillis() && it.matches(url) }.map(Cookie::toString)

    @Synchronized fun responseSnapshot(url: HttpUrl): List<String> = cookies.values.map { it.second }
        .filter { it.expiresAt > System.currentTimeMillis() && browserMatches(it, url) }.map(Cookie::toString)

    private fun browserMatches(cookie: Cookie, url: HttpUrl): Boolean {
        val loopback = url.host in setOf("localhost", "127.0.0.1", "::1")
        return cookie.matches(if (cookie.secure && loopback) url.newBuilder().scheme("https").build() else url)
    }

    /** A trusted browser snapshot includes HttpOnly cookies and their original attributes. */
    @Synchronized fun replaceBrowserSnapshot(url: HttpUrl, values: List<String>, completeMetadata: Boolean,
        expectedSeedVersion: Long? = null) {
        if (expectedSeedVersion != null && expectedSeedVersion != seedVersion) return
        require(values.size <= 256 && values.sumOf(String::length) <= 65536)
        val parsed = values.mapNotNull { Cookie.parse(url, it) }
        // Chromium permits Secure cookies on trustworthy loopback HTTP origins. Validate
        // domain/path here; normal HTTP dispatch must still enforce the Secure attribute.
        require(parsed.all { browserMatches(it, url) })
        require(completeMetadata || parsed.none { it.persistent })
        val before = snapshot()
        val beforeBrowserOnly = browserOnly.toSet()
        cookies.entries.removeAll { browserMatches(it.value.second, url) }
        try {
            val headers = okhttp3.Headers.Builder()
            values.forEach { headers.add("Set-Cookie", it) }
            if (parsed.isEmpty()) {
                val saved = encode(cookies.values)
                check(storage.write("cookies", saved) is StorageResult.Value)
            } else save(url, headers.build(), fromBrowser = true)
            browserOnly.retainAll(cookies.keys)
            // OkHttp's Cookie model cannot retain SameSite, even with complete metadata.
            // Chromium owns its original cookies; only HTTP/rule updates are sent back.
        } catch (failure: Exception) {
            restoreMemory(before); browserOnly.clear(); browserOnly.addAll(beforeBrowserOnly); throw failure
        }
    }

    @Synchronized fun inherit(previous: SourceCookies) = synchronized(previous) {
        restoreMemory(previous.snapshot())
        browserOnly.clear(); browserOnly.addAll(previous.browserOnly)
    }

    @Synchronized fun restoreMemory(snapshot: List<Pair<String, Cookie>>) {
        cookies.clear()
        snapshot.forEach { cookies[key(it.second)] = it }
        browserOnly.retainAll(cookies.keys)
    }
}

