package indi.renakoni.nextvol.data.bangumi

import indi.renakoni.nextvol.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only the HTTP status is retained; upstream error bodies can contain account information. */
class BangumiApiException(val status: Int, val retryAfterSeconds: Long = 0) : IOException("Bangumi HTTP $status")
class BangumiResponseException : IOException("Invalid Bangumi response")
class BangumiLinkException : IllegalArgumentException("Invalid Bangumi subject link")

@Singleton
class BangumiApi internal constructor(private val client: OkHttpClient, private val baseUrl: HttpUrl) {
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    @Inject constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build(), "https://api.bgm.tv/".toHttpUrl())

    suspend fun me(token: String): BangumiUser {
        BangumiAccountStore.validateToken(token)
        val temporary = BangumiSession(BangumiUser(0, ""), "validation", token)
        return try { decode(request("GET", "v0/me", session = temporary)!!) }
        finally { temporary.revoke() }
    }

    suspend fun me(session: BangumiSession): BangumiUser = decode(request("GET", "v0/me", session = session)!!)

    suspend fun search(query: String, offset: Int = 0): BangumiSearchPage {
        val body = buildJsonObject {
            put("keyword", query)
            put("sort", "match")
            put("filter", buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive(1)))) })
        }
        return decode(request("POST", "v0/search/subjects?limit=10&offset=$offset", body)!!)
    }

    suspend fun subject(id: Int): BangumiSubject = decode(request("GET", "v0/subjects/$id")!!)
    suspend fun related(id: Int): List<BangumiRelatedSubject> = decode(request("GET", "v0/subjects/$id/subjects")!!)
    suspend fun people(id: Int): List<BangumiPerson> = decode(request("GET", "v0/subjects/$id/persons")!!)

    suspend fun collection(session: BangumiSession, id: Int): BangumiCollection? {
        val response = request("GET", "v0/users/${session.user.id}/collections/$id", session = session, allowMissing = true)
        return response?.let { decode(it) }
    }

    private inline fun <reified T> decode(text: String): T =
        try { bangumiJson.decodeFromString<T>(text) }
        catch (_: SerializationException) { throw BangumiResponseException() }

    suspend fun createCollection(session: BangumiSession, id: Int, private: Boolean) {
        request("POST", "v0/users/-/collections/$id", buildJsonObject { put("type", 3); put("private", private) }, session)
    }

    suspend fun resumeCollection(session: BangumiSession, id: Int) {
        request("PATCH", "v0/users/-/collections/$id", buildJsonObject { put("type", 3) }, session)
    }

    suspend fun updateVolumes(session: BangumiSession, id: Int, count: Int) {
        require(count >= 0)
        request("PATCH", "v0/users/-/collections/$id", buildJsonObject { put("vol_status", count) }, session)
    }

    private suspend fun request(method: String, path: String, body: JsonObject? = null,
        session: BangumiSession? = null, allowMissing: Boolean = false): String? = withContext(Dispatchers.IO) {
        val url = requireNotNull(baseUrl.resolve(path))
        require(url.host == baseUrl.host && url.scheme == baseUrl.scheme)
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Renakoni/NextVol/${BuildConfig.VERSION_NAME} (Android) (https://github.com/Renakoni/hnovel)")
            // Bangumi's write endpoints reject the charset suffix added by OkHttp's String overload.
            .method(method, body?.toString()?.toByteArray(Charsets.UTF_8)?.toRequestBody("application/json".toMediaType()))
        val call = session?.request(builder, transport::newCall) ?: transport.newCall(builder.build())
        try {
            call.await().use { response ->
                session?.checkActive()
                if (allowMissing && response.code == 404) return@withContext null
                if (!response.isSuccessful) throw BangumiApiException(response.code,
                    response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
                response.body.string()
            }
        } finally { session?.finished(call) }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    companion object {
        fun subjectId(input: String): Int? {
            input.trim().toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            val url = input.trim().toHttpUrlOrNullSafe() ?: return null
            if (url.host !in setOf("bgm.tv", "bangumi.tv", "chii.in") || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            return Regex("^/subject/([0-9]+)/?$").matchEntire(url.encodedPath)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        }
        private fun String.toHttpUrlOrNullSafe(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
    }
}
