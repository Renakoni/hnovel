package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import com.github.michaelbull.result.*
import hnovel.content.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.DISCOVERY_SEARCH_PREFIX
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** One adapter per page. The source runtime still owns revision/account/network authority. */
internal class RuleDiscoveryProvider(private val source: RuleSource,
    private val session: RuleDiscoverySession = source.openDiscovery(java.util.UUID.randomUUID().toString())) : DiscoveryProvider {
    override val hasFeed get() = source.canDiscover
    override val hasCategories get() = source.canDiscover
    override val hasInteractions = true
    override var failureField: String? = null
        private set
    private var current: DiscoveryCatalog? = null
    override fun openSession(id: String, values: Map<String, String>, environment: DiscoveryEnvironment) =
        RuleDiscoveryProvider(source, source.openDiscovery(id, values, RuleDiscoveryEnvironment(environment.themeMode,
            Json.parseToJsonElement(environment.themeJson).jsonObject, Json.parseToJsonElement(environment.readingJson).jsonObject)))

    override suspend fun catalog(refresh: Boolean) = request {
        map(session.catalog(refresh)).also { current = it }
    }
    override suspend fun categories() = catalog().map { it.categories }
    override suspend fun feed() = request {
        val catalog = map(session.catalog()).also { current = it }
        val first = catalog.categories.firstOrNull { it.target.isNotBlank() }
        val preview = first?.let { session.page(it.target, 1, catalog.values).take(6).map(::book) }.orEmpty()
        catalog.categories.map { category -> DiscoverySection(category.id, category.title,
            if (category == first) preview else emptyList(), category.target.takeIf(String::isNotBlank), category.id) }
    }
    override fun filters(target: String) = if (target.startsWith(DISCOVERY_SEARCH_PREFIX)) emptyList() else current?.filters.orEmpty()

    override suspend fun page(request: DiscoveryRequest) = request {
        val page = request.cursor?.toIntOrNull() ?: if (request.cursor == null) 1
            else throw SourceContentException(ContentError.InvalidRule, "ruleExplore.page")
        if (page !in 1..64) throw SourceContentException(ContentError.Limit, "ruleExplore.page")
        val books = if (request.target.startsWith(DISCOVERY_SEARCH_PREFIX)) source.search(request.target.removePrefix(DISCOVERY_SEARCH_PREFIX), page)
            else session.page(request.target, page, request.filters)
        DiscoveryPage(books.map(::book), if (books.isEmpty()) null else (page + 1).toString())
    }

    override suspend fun interact(id: String, value: String?, longClick: Boolean) = request {
        val updated = session.interact(id, value, longClick)
        val catalog = map(updated.catalog).also { current = it }
        DiscoveryUpdate(catalog, updated.actions.mapNotNull { action -> when (action.kind) {
            "login" -> DiscoveryAction.Login
            "settings" -> DiscoveryAction.Settings
            "configuration" -> null // Already persisted by the session; updated.refresh redraws this page.
            "results" -> DiscoveryAction.Results(action.value, action.title)
            "search" -> DiscoveryAction.Results(DISCOVERY_SEARCH_PREFIX + action.value, action.value)
            "browser" -> DiscoveryAction.Browser(action.value, action.title, action.html, action.script)
            else -> throw SourceContentException(ContentError.InvalidRule, "discovery.action")
        } }, updated.refresh)
    }

    override suspend fun openBrowser(action: DiscoveryAction.Browser) = request {
        source.openDiscoveryBrowser(action.url, action.html, action.script, action.title)
    }

    private fun map(catalog: RuleDiscoveryCatalog) = DiscoveryCatalog(
        catalog.rows.filter { it.type == "url" }.map { DiscoveryCategory(it.id, it.title, it.url) },
        catalog.rows.mapNotNull { row -> when (row.type) {
            "text" -> DiscoveryFilter.Text(row.id, row.title, row.default)
            "toggle", "select" -> DiscoveryFilter.Choice(row.id, row.title, row.choices.associateWith { it }, row.default)
            else -> null
        } }, catalog.values, catalog.rows.filter { it.type == "button" }.map { DiscoveryButton(it.id, it.title) })

    private fun book(book: RuleBook) = DiscoveryBook(book.id, book.title, book.author, book.coverUrl)
    private suspend fun <T> request(block: suspend () -> T): Result<T, DiscoveryError> = try { failureField = null; Ok(block()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SourceContentException) { failureField = failure.field; Err(when (failure.code) {
        ContentError.MissingCapability -> DiscoveryError.Unsupported
        ContentError.LoginRequired, ContentError.BrowserRequired -> DiscoveryError.AuthenticationRequired
        ContentError.PermissionDenied -> DiscoveryError.PermissionDenied
        ContentError.Network -> DiscoveryError.Network
        ContentError.Unavailable -> DiscoveryError.Unavailable
        else -> DiscoveryError.InvalidRules
    }) }
}
