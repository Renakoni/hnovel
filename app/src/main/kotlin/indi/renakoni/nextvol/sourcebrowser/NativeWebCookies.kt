package indi.renakoni.nextvol.sourcebrowser

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The compatibility input is a Cookie request header, not a Set-Cookie attribute list. */
internal fun nativeWebCookieUpdates(url: String, header: String, snapshot: NativeCookieSnapshot): List<String> {
    require(header.length <= 65536)
    val target = url.toHttpUrl()
    val pairs = linkedMapOf<String, String>()
    header.split(';').map(String::trim).filter(String::isNotEmpty).forEach { pair ->
        val split = pair.indexOf('=')
        require(split > 0)
        val name = pair.substring(0, split)
        val value = pair.substring(split + 1)
        require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it < ' ' || it == '' })
        Cookie.Builder().name(name).value(value).hostOnlyDomain(target.host).build()
        pairs[name] = value
    }
    require(pairs.size <= 256)
    // Snapshot entries already apply to the URL; only their leading name=value pair is compared.
    fun pair(cookie: String) = cookie.substringBefore(';').trim().split('=', limit = 2).takeIf { it.size == 2 }
    val updates = pairs.flatMap { (name, value) ->
        val existing = snapshot.cookies.filter { pair(it)?.get(0) == name }
        if (existing.isEmpty()) listOf(Cookie.Builder().name(name).value(value).hostOnlyDomain(target.host).path("/").build().toString())
        else existing.mapNotNull { cookie ->
            if (pair(cookie)?.get(1) == value) null else {
                // A header-only snapshot cannot safely reconstruct HttpOnly, SameSite or scope.
                check(snapshot.completeMetadata) { "Cookie attributes unavailable" }
                "$name=$value" + cookie.indexOf(';').takeIf { it >= 0 }?.let { cookie.substring(it) }.orEmpty()
            }
        }
    }
    require(updates.size <= 256 && updates.sumOf(String::length) <= 65536)
    return updates
}
