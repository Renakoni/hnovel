package indi.renakoni.nextvol.data.web.rules

import com.github.michaelbull.result.*
import hnovel.content.*
import indi.renakoni.nextvol.data.web.DISCOVERY_SEARCH_PREFIX
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** One adapter per page. The source runtime still owns revision/account/network authority. */
internal class RuleDiscoveryProvider(private val source: RuleSource,
    private val session: RuleDiscoverySession = source.openDiscovery(java.util.UUID.randomUUID().toString()),
    private val recovery: RuleRequestRecovery? = null) : DiscoveryProvider {
    override val hasFeed get() = source.canFeed && (current?.takeIf {
        // Empty/login/transient responses cannot prove that the source is category-only.
        it.homepage != null || it.rows.any { row -> row.type == "url" && row.url.isNotBlank() }
    }?.let { RuleDiscoveryClassifier.feed(it).isNotEmpty() } ?: true)
    override val hasCategories get() = source.canCategorize
    override val hasInteractions = true
    override var failureField: String? = null
        private set
    override var permissionFailure: DiscoveryPermission? = null
        private set
    private var current: RuleDiscoveryCatalog? = null
    private data class PageKey(val target: String, val filters: Map<String, String>)
    private val pages = object : LinkedHashMap<PageKey, RuleListSession>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageKey, RuleListSession>) = size > 8
    }
    override fun openSession(id: String, values: Map<String, String>, environment: DiscoveryEnvironment) =
        RuleDiscoveryProvider(source, source.openDiscovery(id, values, RuleDiscoveryEnvironment(environment.themeMode,
            Json.parseToJsonElement(environment.themeJson).jsonObject, Json.parseToJsonElement(environment.readingJson).jsonObject)), recovery)

    override suspend fun catalog(refresh: Boolean) = request {
        map(session.catalog(refresh).also { current = it })
    }
    override suspend fun homepageCatalog(refresh: Boolean) = request {
        map(session.catalog(refresh, homepage = true).also { current = it })
    }
    override suspend fun categories() = catalog().map { it.categories }
    override suspend fun feed() = feedUpdates().last()
    override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
        val definition = request { session.catalog(homepage = true) }
            .getOrElse { emit(Err(it)); return@flow }
        current = definition
        val catalog = map(definition)
        val entries = RuleDiscoveryClassifier.feed(definition)
        val sections = mutableListOf<DiscoverySection>()
        for (category in entries) {
            val preview = request { session.openPages(category.url, catalog.values).page(1) }
            val page = preview.get()
            val failure = preview.getError()?.let { DiscoveryPreviewFailure(it, failureField, permissionFailure) }
                ?: if (page?.books.isNullOrEmpty() && page?.nextCursor == null)
                    DiscoveryPreviewFailure(DiscoveryError.InvalidResponse, "ruleExplore.bookList") else null
            sections += DiscoverySection(category.id, category.title, page?.books.orEmpty().take(6).map(::book), category.url,
                category.id.takeIf { definition.homepage == null }, failure)
            // A failed preview belongs to its entry, not to the successful catalogue snapshot.
            failureField = null; permissionFailure = null
            // Recovery belongs to the current module, so a later challenge does not replay earlier previews.
            emit(Ok(sections.toList()))
        }
        if (entries.isEmpty()) {
            if (hasFeed) { failureField = "exploreUrl"; emit(Err(DiscoveryError.InvalidResponse)) }
            else emit(Ok(emptyList()))
        }
    }
    override fun filters(target: String) = if (target.startsWith(DISCOVERY_SEARCH_PREFIX)) emptyList() else
        current?.rows.orEmpty().filter { row -> row.targetPrefixes.isEmpty() || row.targetPrefixes.any(target::startsWith) }.mapNotNull(::filter)

    override suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> {
        val key = PageKey(request.target, request.filters.toMap())
        val pager = synchronized(pages) {
            if (request.cursor == null) pages.remove(key)
            pages.getOrPut(key) {
                if (request.target.startsWith(DISCOVERY_SEARCH_PREFIX))
                    source.openSearchPages(request.target.removePrefix(DISCOVERY_SEARCH_PREFIX))
                else session.openPages(request.target, key.filters)
            }
        }
        return request {
            val page = pager.page(request.cursor)
            DiscoveryPage(page.books.map(::book), page.nextCursor)
        }
    }

    override suspend fun interact(id: String, value: String?, longClick: Boolean) = request(retry = false) {
        val updated = session.interact(id, value, longClick)
        val catalog = map(updated.catalog.also { current = it })
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

    override suspend fun openBrowser(action: DiscoveryAction.Browser) = request(retry = false) {
        source.openDiscoveryBrowser(action.url, action.html, action.script, action.title)
    }

    private fun map(catalog: RuleDiscoveryCatalog) = DiscoveryCatalog(
        catalog.rows.filter { it.type == "url" }
            .map { DiscoveryCategory(it.id, it.title, it.url) },
        catalog.rows.filter { it.targetPrefixes.isEmpty() }.mapNotNull(::filter),
        catalog.values, catalog.rows.filter { it.type == "button" }.map { DiscoveryButton(it.id, it.title) })

    private fun filter(row: RuleDiscoveryRow) = when (row.type) {
            "text" -> DiscoveryFilter.Text(row.id, row.title, row.default)
            "toggle", "select" -> DiscoveryFilter.Choice(row.id, row.title, row.choices.associateWith { it }, row.default)
            else -> null
        }

    private fun book(book: RuleBook) = DiscoveryBook(book.id, book.title, book.author, book.coverUrl)
    private suspend fun <T> request(retry: Boolean = true, block: suspend () -> T): Result<T, DiscoveryError> = try {
        failureField = null; permissionFailure = null
        Ok(if (recovery == null || !retry) block() else recovery.execute(block))
    }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SourceContentException) {
        failureField = failure.field
        permissionFailure = failure.denial?.let { DiscoveryPermission(it.origin, it.kind.name) }
        Err(when (failure.code) {
        ContentError.MissingCapability -> DiscoveryError.Unsupported
        ContentError.LoginRequired -> DiscoveryError.AuthenticationRequired
        ContentError.BrowserRequired -> if (failure.verification?.kind == hnovel.network.BrowserChallengeKind.Login)
            DiscoveryError.AuthenticationRequired else DiscoveryError.VerificationRequired
        ContentError.PermissionDenied -> DiscoveryError.PermissionDenied
        ContentError.AddressDenied -> DiscoveryError.AddressDenied
        ContentError.Dns -> DiscoveryError.Dns
        ContentError.RouteUnavailable -> DiscoveryError.RouteUnavailable
        ContentError.RouteUnsupported -> DiscoveryError.RouteUnsupported
        ContentError.Certificate -> DiscoveryError.Certificate
        ContentError.Network -> DiscoveryError.Network
        ContentError.Unavailable -> DiscoveryError.Unavailable
        ContentError.Limit -> DiscoveryError.Limit
        else -> DiscoveryError.InvalidRules
    }) }
}
