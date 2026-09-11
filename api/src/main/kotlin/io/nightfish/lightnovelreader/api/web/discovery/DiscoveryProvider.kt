package io.nightfish.lightnovelreader.api.web.discovery

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result

/** Source-local data only. The host binds book IDs and targets to the owning runtime. */
data class DiscoveryBook(val remoteId: String, val title: String, val author: String = "", val coverUrl: String = "")
data class DiscoverySection(val id: String, val title: String, val books: List<DiscoveryBook>, val more: String? = null)
data class DiscoveryCategory(val id: String, val title: String, val target: String)

/** Null cursor starts a list; null nextCursor ends it, even if the last page is empty. */
data class DiscoveryRequest(val target: String, val cursor: String? = null, val filters: Map<String, String> = emptyMap())
data class DiscoveryPage(val books: List<DiscoveryBook>, val nextCursor: String? = null)

sealed interface DiscoveryFilter {
    val id: String
    val title: String
    data class Choice(override val id: String, override val title: String,
        val options: Map<String, String>, val defaultValue: String) : DiscoveryFilter
    data class Toggle(override val id: String, override val title: String, val defaultValue: Boolean = false) : DiscoveryFilter
    data class Number(override val id: String, override val title: String,
        val min: Int, val max: Int, val defaultValue: Int = min) : DiscoveryFilter
}

enum class DiscoveryError {
    Unsupported, AuthenticationRequired, PermissionDenied, InvalidRules, Network,
    InvalidRequest, InvalidResponse, Unavailable,
}

/** Implementations must not keep page/filter state. Each request is an independent snapshot. */
interface DiscoveryProvider {
    val hasFeed: Boolean get() = false
    val hasCategories: Boolean get() = false
    suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> = Err(DiscoveryError.Unsupported)
    suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> = Err(DiscoveryError.Unsupported)
    fun filters(target: String): List<DiscoveryFilter> = emptyList()
    suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> = Err(DiscoveryError.Unsupported)
}
