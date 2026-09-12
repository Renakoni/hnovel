package indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

data class DiscoveryPageContent(
    val categories: List<SourceDiscoveryCategory> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: DiscoveryError? = null,
    val scroll: DiscoveryScroll = DiscoveryScroll(),
    val filters: List<DiscoveryFilter> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val buttons: List<DiscoveryButton> = emptyList(),
    val acting: Boolean = false,
    val errorField: String? = null,
    val errorPermission: DiscoveryPermission? = null,
    val sections: List<SourceDiscoverySection> = emptyList(),
    val resetId: Long = 0,
)

data class DiscoveryPageState(
    val sources: List<SourceListing> = emptyList(),
    val selected: Identifier? = null,
    val content: Map<Identifier, DiscoveryPageContent> = emptyMap(),
    val loadingSources: Boolean = false,
)

data class DiscoveryCommand(val source: Identifier, val epoch: Long, val action: DiscoveryAction, val values: Map<String, String>)

/** Each navigation entry owns its source selection, drafts, requests and browser handoff. */
abstract class DiscoveryPageViewModel(
    private val registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
    private val capability: SourceCapability,
) : ViewModel() {
    private val key = if (capability == SourceCapability.Categories) "category" else "explore"
    private var contentId = 0L
    private val mutableState = MutableStateFlow(DiscoveryPageState(loadingSources = true))
    val state = mutableState.asStateFlow()
    private var versions = emptyMap<Identifier, DiscoveryVersion>()
    // category./explore. keys own this entry's persisted selection. Bare namespace/sourceId
    // are Route.Main.Categories arguments, used only as the initial fallback. Later shortcuts
    // to a restored entry must be consumed through select(), not by rewriting route arguments.
    private var requested = (saved.get<String>("$key.namespace") ?: saved.get<String>("namespace"))?.let { namespace ->
        (saved.get<String>("$key.source") ?: saved.get<String>("sourceId"))?.let { Identifier(namespace, it) }
    }
    private var active = false
    private var pending: Job? = null
    private var browser: Job? = null
    private var browserToken: Any? = null
    private var serial = 0L
    private var actionEpoch = 0L
    private val sessions = mutableMapOf<Identifier, SourceDiscovery>()
    private val refreshCatalog = mutableSetOf<Identifier>()
    private val outgoing = Channel<DiscoveryCommand>(Channel.BUFFERED)
    val commands = outgoing.receiveAsFlow()
    private val pageId = saved.get<String>("$key.session") ?: UUID.randomUUID().toString().also { saved["$key.session"] = it }
    private var environment = DiscoveryEnvironment()

    init {
        viewModelScope.launch {
            combine(registry.sources, accounts.changes) { sources, generations ->
                discoverySources(sources, capability) to generations
            }.collect { (sources, generations) ->
                val next = sources.associate { it.metadata.id to it.version(generations) }
                val selected = selectedSource(sources, requested)
                val changed = selected != state.value.selected || versions[selected] != next[selected]
                if (changed) cancelLoad()
                // cancelLoad also clears loading flags; do not restore a snapshot captured before it.
                val content = state.value.content.filterKeys { it in next && versions[it] == next[it] }
                sessions.keys.retainAll(content.keys)
                refreshCatalog.retainAll(next.keys)
                versions = next
                mutableState.value = DiscoveryPageState(sources, selected, content)
                if (selected != null) remember(selected)
                if (active) load()
            }
        }
    }

    fun setActive(value: Boolean, retainBrowser: Boolean = false) {
        active = value
        if (value) load() else cancelLoad(retainBrowser)
    }

    fun select(id: Identifier) {
        if (id == state.value.selected || state.value.sources.none { it.metadata.id == id }) return
        cancelLoad()
        remember(id)
        mutableState.value = state.value.copy(selected = id)
        if (active) load()
    }

    fun refresh() {
        val id = state.value.selected ?: return
        cancelLoad()
        refreshCatalog += id
        put(id, (state.value.content[id] ?: newContent()).copy(loaded = false, loading = false, error = null))
        if (active) load()
    }

    fun environment(value: DiscoveryEnvironment) {
        if (environment == value) return
        environment = value
        cancelLoad()
        sessions.clear()
        mutableState.value = state.value.copy(content = state.value.content.mapValues { (_, page) -> page.copy(loaded = false, error = null) })
    }

    fun accepts(command: DiscoveryCommand) = active && state.value.selected == command.source && command.epoch == actionEpoch

    fun interact(id: String, value: String? = null, longClick: Boolean = false) {
        val source = state.value.selected ?: return
        val previous = state.value.content[source] ?: return
        val discovery = sessions[source] ?: return
        if (!active || previous.loading || previous.acting) return
        cancelLoad()
        val token = ++serial
        val epoch = actionEpoch
        put(source, previous.copy(acting = true, error = null))
        pending = viewModelScope.launch {
            val result = discoveryRequest { discovery.interact(id, value, longClick) }
            if (serial != token || !active || state.value.selected != source) return@launch
            result.onErr { put(source, state.value.content.getValue(source).copy(acting = false, error = it, errorField = discovery.failureField, errorPermission = discovery.permissionFailure)) }
                .onOk { update ->
                    put(source, applyCatalog(state.value.content.getValue(source), update.catalog))
                    // Record invalidation before an emitted action can stop/cancel this page's work.
                    if (update.refresh || capability == SourceCapability.Explore) {
                        if (update.refresh) refreshCatalog += source
                        put(source, state.value.content.getValue(source).copy(loaded = false))
                    }
                    update.actions.forEach { outgoing.send(DiscoveryCommand(source, epoch, it, update.catalog.values)) }
                    if ((update.refresh || capability == SourceCapability.Explore) && active) load()
                }
        }
    }

    fun openBrowser(command: DiscoveryCommand) {
        if (!accepts(command)) return
        val action = command.action as? DiscoveryAction.Browser ?: return
        val discovery = sessions[command.source] ?: return
        // A queued catalogue refresh must not race the browser's page/session ownership.
        serial++
        pending?.cancel()
        pending = null
        val token = Any()
        browserToken = token
        browser?.cancel()
        put(command.source, state.value.content.getValue(command.source).copy(loading = false, acting = true, error = null, errorField = null, errorPermission = null))
        browser = viewModelScope.launch {
            val result = discoveryRequest { discovery.openBrowser(action) }
            // Its Activity covers this destination. Completion may precede onStart, after UI
            // command epochs have expired; only this captured browser request may finish the handoff.
            if (browserToken !== token) return@launch
            browserToken = null
            browser = null
            result.onErr { put(command.source, state.value.content.getValue(command.source).copy(acting = false, error = it, errorField = discovery.failureField, errorPermission = discovery.permissionFailure)) }
                .onOk { refresh() }
        }
    }

    fun scroll(id: Identifier, position: DiscoveryScroll) {
        state.value.content[id]?.let { put(id, it.copy(scroll = position)) }
    }

    fun result(category: SourceDiscoveryCategory): Route.Main.DiscoveryResults? {
        val id = state.value.selected ?: return null
        if (category.target.sourceId != id || category.target.target.isBlank() || category !in state.value.content[id]?.categories.orEmpty()) return null
        // Rule input IDs equal the original form title/infoMap key, not viewName or URL-row digests.
        // filtersJson relies on this to seed both result filter normalization and the page-local draft.
        return Route.Main.DiscoveryResults(id.namespace, id.id, category.target.target, category.title,
            UUID.randomUUID().toString(), category.id, Json.encodeToString(state.value.content[id]?.values.orEmpty()))
    }

    private fun remember(id: Identifier) {
        requested = id
        saved["$key.namespace"] = id.namespace
        saved["$key.source"] = id.id
    }

    private fun cancelLoad(retainBrowser: Boolean = false) {
        serial++
        actionEpoch++
        pending?.cancel()
        pending = null
        if (!retainBrowser) {
            browserToken = null
            browser?.cancel()
            browser = null
        }
        state.value.selected?.let { id -> state.value.content[id]?.let { put(id, it.copy(loading = false, acting = browserToken != null)) } }
    }

    private fun put(id: Identifier, value: DiscoveryPageContent) {
        mutableState.value = state.value.copy(content = state.value.content + (id to value))
    }

    private fun applyCatalog(previous: DiscoveryPageContent, catalog: SourceDiscoveryCatalog) = previous.copy(
        categories = catalog.categories, filters = catalog.filters, values = catalog.values, buttons = catalog.buttons,
        loaded = true, loading = false, acting = false, error = null, errorField = null, errorPermission = null)

    protected open suspend fun loadFeed(discovery: SourceDiscovery) = discovery.feed()

    private fun newContent() = DiscoveryPageContent(resetId = ++contentId)

    private fun load() {
        val id = state.value.selected ?: return
        val previous = state.value.content[id] ?: newContent()
        if (previous.loaded || previous.loading || previous.acting || previous.error != null) return
        val token = ++serial
        put(id, previous.copy(loading = true))
        pending = viewModelScope.launch {
            val result = discoveryRequest {
                val discovery = sessions[id] ?: registry.discovery(id).getOrElse { return@discoveryRequest com.github.michaelbull.result.Err(it) }
                    .forSession(pageId, previous.values, environment).also { sessions[id] = it }
                var content = previous
                // Native feeds need no catalogue request. Rules declare their interactive catalogue.
                if (capability == SourceCapability.Categories || discovery.hasInteractions) {
                    val catalog = discovery.catalog(id in refreshCatalog).getOrElse { return@discoveryRequest Err(it) }
                    content = applyCatalog(content, catalog)
                }
                if (capability == SourceCapability.Explore) {
                    val sections = loadFeed(discovery).getOrElse { return@discoveryRequest Err(it) }
                    content = content.copy(sections = sections)
                }
                Ok(content.copy(loaded = true, loading = false, acting = false, error = null, errorField = null, errorPermission = null))
            }
            if (serial != token || !active || state.value.selected != id) return@launch
            val current = state.value.content[id] ?: previous
            result.onOk { refreshCatalog -= id; put(id, it.copy(scroll = current.scroll)) }
                .onErr { put(id, current.copy(error = it, loading = false, errorField = sessions[id]?.failureField, errorPermission = sessions[id]?.permissionFailure)) }
        }
    }
}
