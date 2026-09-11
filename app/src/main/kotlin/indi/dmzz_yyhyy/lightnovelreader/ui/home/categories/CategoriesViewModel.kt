package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.onErr
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.*
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
import javax.inject.Inject

data class CategoryContent(
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
)

data class CategoriesState(
    val sources: List<SourceListing> = emptyList(),
    val selected: Identifier? = null,
    val content: Map<Identifier, CategoryContent> = emptyMap(),
)

data class DiscoveryCommand(val source: Identifier, val epoch: Long, val action: DiscoveryAction, val values: Map<String, String>)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CategoriesState())
    val state = mutableState.asStateFlow()
    private var versions = emptyMap<Identifier, DiscoveryVersion>()
    private var requested = saved.get<String>("category.namespace")?.let { namespace ->
        saved.get<String>("category.source")?.let { Identifier(namespace, it) }
    }
    private var active = false
    private var pending: Job? = null
    private var browser: Job? = null
    private var serial = 0L
    private var actionEpoch = 0L
    private val sessions = mutableMapOf<Identifier, SourceDiscovery>()
    private val refreshCatalog = mutableSetOf<Identifier>()
    private val outgoing = Channel<DiscoveryCommand>(Channel.BUFFERED)
    val commands = outgoing.receiveAsFlow()
    private val pageId = saved.get<String>("category.session") ?: UUID.randomUUID().toString().also { saved["category.session"] = it }
    private var environment = DiscoveryEnvironment()

    init {
        viewModelScope.launch {
            combine(registry.sources, accounts.changes) { sources, generations ->
                discoverySources(sources, SourceCapability.Categories) to generations
            }.collect { (sources, generations) ->
                val next = sources.associate { it.metadata.id to it.version(generations) }
                val content = state.value.content.filterKeys { it in next && versions[it] == next[it] }
                sessions.keys.retainAll(content.keys)
                val selected = selectedSource(sources, requested)
                val changed = selected != state.value.selected || versions[selected] != next[selected]
                if (changed) cancelLoad()
                versions = next
                mutableState.value = CategoriesState(sources, selected, content)
                if (selected != null) remember(selected)
                if (active) load()
            }
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) load() else cancelLoad()
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
        put(id, (state.value.content[id] ?: CategoryContent()).copy(loaded = false, loading = false, error = null))
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
            result.onErr { put(source, state.value.content.getValue(source).copy(acting = false, error = it, errorField = discovery.failureField)) }
                .onOk { update ->
                    put(source, applyCatalog(state.value.content.getValue(source), update.catalog))
                    update.actions.forEach { outgoing.send(DiscoveryCommand(source, epoch, it, update.catalog.values)) }
                    if (update.refresh) {
                        refreshCatalog += source
                        put(source, state.value.content.getValue(source).copy(loaded = false))
                        load()
                    }
                }
        }
    }

    fun openBrowser(command: DiscoveryCommand) {
        if (!accepts(command)) return
        val action = command.action as? DiscoveryAction.Browser ?: return
        val discovery = sessions[command.source] ?: return
        browser?.cancel()
        put(command.source, state.value.content.getValue(command.source).copy(acting = true, error = null, errorField = null))
        browser = viewModelScope.launch {
            val result = discoveryRequest { discovery.openBrowser(action) }
            if (!accepts(command)) return@launch
            browser = null
            result.onErr { put(command.source, state.value.content.getValue(command.source).copy(acting = false, error = it, errorField = discovery.failureField)) }
                .onOk { refresh() }
        }
    }

    fun scroll(id: Identifier, position: DiscoveryScroll) {
        state.value.content[id]?.let { put(id, it.copy(scroll = position)) }
    }

    fun result(category: SourceDiscoveryCategory): Route.Main.DiscoveryResults? {
        val id = state.value.selected ?: return null
        if (category.target.sourceId != id || category.target.target.isBlank() || category !in state.value.content[id]?.categories.orEmpty()) return null
        return Route.Main.DiscoveryResults(id.namespace, id.id, category.target.target, category.title,
            UUID.randomUUID().toString(), category.id, Json.encodeToString(state.value.content[id]?.values.orEmpty()))
    }

    private fun remember(id: Identifier) {
        requested = id
        saved["category.namespace"] = id.namespace
        saved["category.source"] = id.id
    }

    private fun cancelLoad() {
        serial++
        actionEpoch++
        pending?.cancel()
        pending = null
        browser?.cancel()
        browser = null
        state.value.selected?.let { id -> state.value.content[id]?.let { put(id, it.copy(loading = false, acting = false)) } }
    }

    private fun put(id: Identifier, value: CategoryContent) {
        mutableState.value = state.value.copy(content = state.value.content + (id to value))
    }

    private fun applyCatalog(previous: CategoryContent, catalog: SourceDiscoveryCatalog) = previous.copy(
        categories = catalog.categories, filters = catalog.filters, values = catalog.values, buttons = catalog.buttons,
        loaded = true, loading = false, acting = false, error = null, errorField = null)

    private fun load() {
        val id = state.value.selected ?: return
        val previous = state.value.content[id] ?: CategoryContent()
        if (previous.loaded || previous.loading || previous.error != null) return
        val token = ++serial
        put(id, previous.copy(loading = true))
        pending = viewModelScope.launch {
            val result = discoveryRequest {
                val discovery = sessions[id] ?: registry.discovery(id).getOrElse { return@discoveryRequest com.github.michaelbull.result.Err(it) }
                    .forSession(pageId, previous.values, environment).also { sessions[id] = it }
                discovery.catalog(id in refreshCatalog)
            }
            if (serial != token || !active || state.value.selected != id) return@launch
            val current = state.value.content[id] ?: previous
            result.onOk { refreshCatalog -= id; put(id, applyCatalog(current, it)) }
                .onErr { put(id, current.copy(error = it, loading = false, errorField = sessions[id]?.failureField)) }
        }
    }
}
