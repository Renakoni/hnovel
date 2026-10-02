package hnovel.network

import okhttp3.Interceptor
import okhttp3.Response

/** Explicit source/request identities win. An absent or empty value is not an identity. */
fun resolvedUserAgent(value: String?, fallback: String = DESKTOP_USER_AGENT): String =
    value?.takeIf { it.isNotBlank() } ?: fallback

class DefaultUserAgentInterceptor(private val fallback: String = DESKTOP_USER_AGENT) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = request.header("User-Agent")
        return chain.proceed(if (current.isNullOrBlank()) request.newBuilder()
            .header("User-Agent", resolvedUserAgent(current, fallback)).build() else request)
    }
}
