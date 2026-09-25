package io.nightfish.lightnovelreader.api.web.discovery

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Source-local data only. The host binds book IDs and targets to the owning runtime. */
data class DiscoveryBook(val remoteId: String, val title: String, val author: String = "", val coverUrl: String = "")
data class DiscoverySection(val id: String, val title: String, val books: List<DiscoveryBook>,
    val more: String? = null, val categoryId: String? = null, val previewFailure: DiscoveryPreviewFailure? = null,
    val previewLoading: Boolean) {
    // Retain the constructor and copy entrypoints used by already compiled providers.
    constructor(id: String, title: String, books: List<DiscoveryBook>, more: String? = null,
        categoryId: String? = null, previewFailure: DiscoveryPreviewFailure? = null) :
        this(id, title, books, more, categoryId, previewFailure, false)
    fun copy(id: String = this.id, title: String = this.title, books: List<DiscoveryBook> = this.books,
        more: String? = this.more, categoryId: String? = this.categoryId,
        previewFailure: DiscoveryPreviewFailure? = this.previewFailure) =
        DiscoverySection(id, title, books, more, categoryId, previewFailure, previewLoading)
}
/** A preview can fail while its catalogue entry and other sections remain usable. */
data class DiscoveryPreviewFailure(val error: DiscoveryError, val field: String? = null, val permission: DiscoveryPermission? = null)
data class DiscoveryCategory(val id: String, val title: String, val target: String)

/** Null cursor starts a list; null nextCursor ends it, even if the last page is empty. */
data class DiscoveryRequest(val target: String, val cursor: String? = null, val filters: Map<String, String> = emptyMap())
data class DiscoveryPage(val books: List<DiscoveryBook>, val nextCursor: String? = null)

data class DiscoveryEnvironment(val themeMode: String = "0", val themeJson: String = "{}", val readingJson: String = "{}")
data class DiscoveryButton(val id: String, val title: String)
data class DiscoveryCatalog(val categories: List<DiscoveryCategory>, val filters: List<DiscoveryFilter> = emptyList(),
    val values: Map<String, String> = emptyMap(), val buttons: List<DiscoveryButton> = emptyList())
sealed interface DiscoveryAction {
    data class Results(val target: String, val title: String) : DiscoveryAction
    data class Browser(val url: String, val title: String = "", val html: String? = null, val script: String = "") : DiscoveryAction
    data object Login : DiscoveryAction
    data object Settings : DiscoveryAction
}
data class DiscoveryUpdate(val catalog: DiscoveryCatalog, val actions: List<DiscoveryAction> = emptyList(), val refresh: Boolean = false)

sealed interface DiscoveryFilter {
    val id: String
    val title: String
    data class Choice(override val id: String, override val title: String,
        val options: Map<String, String>, val defaultValue: String) : DiscoveryFilter
    data class Toggle(override val id: String, override val title: String, val defaultValue: Boolean = false) : DiscoveryFilter
    data class Number(override val id: String, override val title: String,
        val min: Int, val max: Int, val defaultValue: Int = min) : DiscoveryFilter
    data class Text(override val id: String, override val title: String, val defaultValue: String = "") : DiscoveryFilter
}

enum class DiscoveryError {
    Unsupported, AuthenticationRequired, PermissionDenied, InvalidRules, Network,
    InvalidRequest, InvalidResponse, Unavailable, Limit, AddressDenied, Dns, RateLimited, VerificationRequired,
    RouteUnavailable, RouteUnsupported, Certificate,
}

/** Source-bound, redacted exact origin; resourceKind is a host resource label, never a URL or header. */
data class DiscoveryPermission(val origin: String, val resourceKind: String)

/** Shared providers are stateless. openSession may create a page-owned provider for rule drafts. */
interface DiscoveryProvider {
    val failureField: String? get() = null
    val permissionFailure: DiscoveryPermission? get() = null
    val hasFeed: Boolean get() = false
    val hasCategories: Boolean get() = false
    /** A feed page also displays this provider's catalogue inputs and actions. */
    val hasInteractions: Boolean get() = false
    suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> = Err(DiscoveryError.Unsupported)
    /** Ordered replacement snapshots; completion ends loading, an error is terminal.
     * Existing providers retain their one-shot feed. Collectors own cancellation. */
    fun feedUpdates(): Flow<Result<List<DiscoverySection>, DiscoveryError>> = flow { emit(feed()) }
    suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> = Err(DiscoveryError.Unsupported)
    fun filters(target: String): List<DiscoveryFilter> = emptyList()
    suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> = Err(DiscoveryError.Unsupported)
    /** Native providers are stateless; rule providers create a draft for this host-owned page. */
    fun openSession(id: String, values: Map<String, String> = emptyMap(), environment: DiscoveryEnvironment = DiscoveryEnvironment()): DiscoveryProvider = this
    suspend fun catalog(refresh: Boolean = false): Result<DiscoveryCatalog, DiscoveryError> = categories().map { DiscoveryCatalog(it) }
    /** Homepage controls may be independent of a remote category catalogue. */
    suspend fun homepageCatalog(refresh: Boolean = false): Result<DiscoveryCatalog, DiscoveryError> = catalog(refresh)
    suspend fun interact(id: String, value: String? = null, longClick: Boolean = false): Result<DiscoveryUpdate, DiscoveryError> = Err(DiscoveryError.Unsupported)
    suspend fun openBrowser(action: DiscoveryAction.Browser): Result<Unit, DiscoveryError> = Err(DiscoveryError.Unsupported)
}

/** Optional independent retry of a known feed entry, including while other previews load.
 * Preview failures belong to the returned section, not the provider's page-level error fields. */
interface DiscoveryPreviewProvider : DiscoveryProvider {
    suspend fun preview(id: String): Result<DiscoverySection, DiscoveryError>
}
