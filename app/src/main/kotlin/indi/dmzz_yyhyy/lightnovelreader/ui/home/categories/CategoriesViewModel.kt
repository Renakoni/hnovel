package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.onErr
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.*
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class CategoryContent(
    val categories: List<SourceDiscoveryCategory> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: DiscoveryError? = null,
    val scroll: DiscoveryScroll = DiscoveryScroll(),
)

data class CategoriesState(
    val sources: List<SourceListing> = emptyList(),
    val selected: Identifier? = null,
    val content: Map<Identifier, CategoryContent> = emptyMap(),
    val loadingSources: Boolean = false,
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CategoriesState(loadingSources = true))
    val state = mutableState.asStateFlow()
    private var versions = emptyMap<Identifier, DiscoveryVersion>()
    private var requested = saved.get<String>("category.namespace")?.let { namespace ->
        saved.get<String>("category.source")?.let { Identifier(namespace, it) }
    }
    private var active = false
    private var pending: Job? = null
    private var serial = 0L

    init {
        viewModelScope.launch {
            combine(registry.sources, accounts.changes) { sources, generations ->
                discoverySources(sources, SourceCapability.Categories) to generations
            }.collect { (sources, generations) ->
                val next = sources.associate { it.metadata.id to it.version(generations) }
                val selected = selectedSource(sources, requested)
                val changed = selected != state.value.selected || versions[selected] != next[selected]
                if (changed) cancelLoad()
                // cancelLoad also clears loading flags; do not restore a snapshot captured before it.
                val content = state.value.content.filterKeys { it in next && versions[it] == next[it] }
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
        put(id, CategoryContent(scroll = state.value.content[id]?.scroll ?: DiscoveryScroll()))
        if (active) load()
    }

    fun scroll(id: Identifier, position: DiscoveryScroll) {
        state.value.content[id]?.let { put(id, it.copy(scroll = position)) }
    }

    fun result(category: SourceDiscoveryCategory): Route.Main.DiscoveryResults? {
        val id = state.value.selected ?: return null
        if (category.target.sourceId != id || category !in state.value.content[id]?.categories.orEmpty()) return null
        return Route.Main.DiscoveryResults(id.namespace, id.id, category.target.target, category.title,
            UUID.randomUUID().toString(), category.id)
    }

    private fun remember(id: Identifier) {
        requested = id
        saved["category.namespace"] = id.namespace
        saved["category.source"] = id.id
    }

    private fun cancelLoad() {
        serial++
        pending?.cancel()
        pending = null
        state.value.selected?.let { id -> state.value.content[id]?.let { put(id, it.copy(loading = false)) } }
    }

    private fun put(id: Identifier, value: CategoryContent) {
        mutableState.value = state.value.copy(content = state.value.content + (id to value))
    }

    private fun load() {
        val id = state.value.selected ?: return
        val previous = state.value.content[id] ?: CategoryContent()
        if (previous.loaded || previous.loading || previous.error != null) return
        val token = ++serial
        put(id, previous.copy(loading = true))
        pending = viewModelScope.launch {
            val result = discoveryRequest { registry.discovery(id).andThen { it.categories() } }
            if (serial != token || !active || state.value.selected != id) return@launch
            val current = state.value.content[id] ?: previous
            result.onOk { put(id, current.copy(categories = it, loading = false, loaded = true)) }
                .onErr { put(id, current.copy(error = it, loading = false)) }
        }
    }
}
