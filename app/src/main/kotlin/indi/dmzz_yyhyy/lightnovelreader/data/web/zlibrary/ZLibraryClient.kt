package indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary

import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRequestException
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.UUID

internal data class ZLibraryBook(val id: String, val title: String, val author: String,
    val language: String, val format: String, val year: String, val size: String,
    val publisher: String, val description: String, val cover: String)
internal data class ZLibraryPage(val books: List<ZLibraryBook>, val next: Int?)

/** The eAPI owns metadata. This adapter never uses href/slugs, download URLs or reader tokens. */
internal class ZLibraryClient(private val session: SourceSession, private val origin: String) {
    suspend fun search(keyword: String, page: Int, language: String? = null, format: String? = null,
        limit: Int = 20): ZLibraryPage {
        if (keyword.isBlank() || keyword.length > 512 || page !in 1..1000 || limit !in 1..50)
            throw SourceRequestException(DiscoveryError.InvalidRequest)
        val fields = linkedMapOf("message" to keyword, "page" to page.toString(), "limit" to limit.toString())
        language?.let { fields["languages[0]"] = it }
        format?.let { fields["extensions[0]"] = it }
        val form = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val json = json("/eapi/book/search", form)
        return parse {
            val books = json.getValue("books").jsonArray
            require(books.size <= limit)
            val pagination = json.getValue("pagination").jsonObject
            require(pagination.getValue("current").jsonPrimitive.int == page)
            val nextValue = pagination.getValue("next").jsonPrimitive
            val next = if (nextValue.booleanOrNull == false || nextValue is JsonNull) null
                else nextValue.int.also { require(it > page && it <= 1000) }
            require(books.isNotEmpty() || next == null)
            ZLibraryPage(books.map { book(it.jsonObject) }, next)
        }
    }

    suspend fun information(id: String): ZLibraryBook {
        if (!BOOK_ID.matches(id)) throw SourceRequestException(DiscoveryError.InvalidRequest)
        val response = json("/eapi/book/$id")
        return parse { book(response.getValue("book").jsonObject).also { require(it.id == id) } }
    }

    suspend fun image(url: String): ByteArray = response(BrokerRequest(UUID.randomUUID().toString(), url,
        kind = ResourceKind.Image, maxResponseBytes = 4 * 1024 * 1024)).let {
        checkStatus(it.status)
        it.body
    }

    private suspend fun json(path: String, form: String? = null): JsonObject {
        val response = response(BrokerRequest(UUID.randomUUID().toString(), origin.trimEnd('/') + path,
            method = if (form == null) "GET" else "POST", body = form,
            headers = if (form == null) emptyMap() else mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            kind = ResourceKind.Api, maxResponseBytes = 2 * 1024 * 1024))
        if (response.status == 401 || response.status == 429 || response.status >= 500) checkStatus(response.status)
        val json = parse { Json.parseToJsonElement(response.text()).jsonObject }
        if (!parse { json["success"]?.jsonPrimitive?.intOrNull == 1 }) {
            // Verified eAPI clients recognize these authentication messages. Unknown errors stay
            // invalid responses; a bot challenge or download quota must not become a login error.
            val error = json["error"].let { if (it is JsonObject) it["message"] else it }
                .let { (it as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase() }
            throw SourceRequestException(when {
                "please login" in error || "authentication required" in error -> DiscoveryError.AuthenticationRequired
                "too many requests" in error || "rate limit" in error -> DiscoveryError.RateLimited
                response.status >= 500 -> DiscoveryError.Network
                else -> DiscoveryError.InvalidResponse
            })
        }
        checkStatus(response.status)
        return json
    }

    private suspend fun response(request: BrokerRequest): BrokerResponse {
        currentCoroutineContext().ensureActive()
        return when (val result = session.execute(request)) {
            is BrokerResult.Success -> result.response
            is BrokerResult.Failure -> throw SourceRequestException(when (result.code) {
                FailureCode.OriginDenied -> DiscoveryError.PermissionDenied
                FailureCode.AddressDenied -> DiscoveryError.AddressDenied
                FailureCode.Dns -> DiscoveryError.Dns
                FailureCode.ResponseTooLarge -> DiscoveryError.Limit
                FailureCode.InvalidRequest -> DiscoveryError.InvalidRequest
                else -> DiscoveryError.Network
            }, result.denial)
        }
    }

    private fun checkStatus(status: Int) {
        if (status !in 200..299) throw SourceRequestException(when (status) {
            401 -> DiscoveryError.AuthenticationRequired
            429 -> DiscoveryError.RateLimited
            else -> DiscoveryError.Network
        })
    }

    private fun book(raw: JsonObject): ZLibraryBook {
        fun value(key: String, max: Int = 512) = (raw[key] as? JsonPrimitive)?.contentOrNull.orEmpty().also { require(it.length <= max) }
        val id = value("id") + "/" + value("hash").lowercase()
        require(BOOK_ID.matches(id))
        val title = plain(value("title", 4096)).also { require(it.isNotBlank()) }
        val cover = value("cover", 4096).let { address ->
            if (address.isBlank()) "" else address.toHttpUrlOrNull()?.takeIf {
                it.username.isEmpty() && it.password.isEmpty()
            }?.toString().orEmpty()
        }
        return ZLibraryBook(id, title, plain(value("author", 4096)), value("language"),
            value("extension").uppercase(), value("year").takeUnless { it == "0" }.orEmpty(),
            value("filesizeString"), plain(value("publisher", 4096)), plain(value("description", 65536)), cover)
    }

    private inline fun <T> parse(block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { throw SourceRequestException(DiscoveryError.InvalidResponse) }
    catch (_: IllegalStateException) { throw SourceRequestException(DiscoveryError.InvalidResponse) }
    catch (_: NoSuchElementException) { throw SourceRequestException(DiscoveryError.InvalidResponse) }

    private fun plain(value: String) = Jsoup.parse(value).wholeText().trim()
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    companion object { private val BOOK_ID = Regex("[1-9][0-9]{0,19}/[0-9a-f]{6,64}") }
}
