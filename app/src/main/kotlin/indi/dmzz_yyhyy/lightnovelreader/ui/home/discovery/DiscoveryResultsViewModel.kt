package indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.github.michaelbull.result.*
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class DiscoveryResultsState(
    val title: String,
    val sourceName: String = "",
    val books: List<SourceDiscoveryBook> = emptyList(),
    val definitions: List<DiscoveryFilter> = emptyList(),
    val filters: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val error: DiscoveryError? = null,
    val scroll: DiscoveryScroll = DiscoveryScroll(),
    val resetId: Long = 0,
)

/** One ViewModel per navigation entry. Only lightweight identity/filter values survive process death. */
@HiltViewModel
class DiscoveryResultsViewModel internal constructor(
    private val registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
    val route: Route.Main.DiscoveryResults,
) : ViewModel() {
    @Inject constructor(registry: WebSourceRegistry, accounts: SourceSessionManager, saved: SavedStateHandle) :
        this(registry, accounts, saved, saved.toRoute<Route.Main.DiscoveryResults>())

    val sourceId = Identifier(route.namespace, route.sourceId)
    private val mutableState = MutableStateFlow(DiscoveryResultsState(route.title, filters =
        kotlin.runCatching { Json.decodeFromString<Map<String, String>>(saved["discovery.filters"] ?: route.filtersJson) }
            .getOrDefault(emptyMap())))
    val state = mutableState.asStateFlow()
    private var version: DiscoveryVersion? = null
    private var active = false
    private var session: DiscoverySession? = null
    private var pending: Job? = null
    private var serial = 0L

    init {
        viewModelScope.launch {
            combine(registry.sources, accounts.changes) { sources, generations ->
                sources.firstOrNull { it.metadata.id == sourceId }?.version(generations)
            }.distinctUntilChanged().collect { next ->
                version = next
                cancelLoad()
                session = null
                mutableState.value = DiscoveryResultsState(route.title,
                    sourceName = next?.metadata?.item?.name.orEmpty(), filters = state.value.filters,
                    resetId = state.value.resetId + 1,
                    error = if (next == null) DiscoveryError.Unavailable else null)
                if (active && next != null) loadMore()
            }
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) cancelLoad()
        else if (!state.value.loaded && state.value.error == null && version != null) loadMore()
    }

    fun refresh() {
        cancelLoad()
        session = null
        mutableState.value = state.value.copy(books = emptyList(), loaded = false, hasMore = false,
            error = null, scroll = DiscoveryScroll(), resetId = state.value.resetId + 1)
        if (active) loadMore()
    }

    fun filter(id: String, value: String) {
        val next = filterValues(state.value.definitions, state.value.filters + (id to value))
        if (next == state.value.filters) return
        mutableState.value = state.value.copy(filters = next)
        saveFilters(next)
        refresh()
    }

    fun scroll(position: DiscoveryScroll) { mutableState.value = state.value.copy(scroll = position) }

    private fun saveFilters(values: Map<String, String>) { saved["discovery.filters"] = Json.encodeToString(values) }

    private fun cancelLoad() {
        serial++
        pending?.cancel()
        pending = null
        mutableState.value = state.value.copy(loading = false)
    }

    fun loadMore() {
        if (!active || state.value.loading || (state.value.loaded && !state.value.hasMore)) return
        val token = ++serial
        mutableState.value = state.value.copy(loading = true, error = null)
        pending = viewModelScope.launch {
            val result = discoveryRequest {
                if (session == null) {
                    val source = registry.discovery(sourceId).getOrElse { return@discoveryRequest Err(it) }
                    if (route.categoryId != null) {
                        val categories = source.categories().getOrElse { return@discoveryRequest Err(it) }
                        if (categories.none { it.id == route.categoryId && it.target.target == route.target })
                            return@discoveryRequest Err(DiscoveryError.InvalidRequest)
                    }
                    val opened = source.open(SourceDiscoveryTarget(sourceId, route.target))
                    val values = filterValues(opened.filters, state.value.filters)
                    opened.reset(values)
                    if (token != serial) return@discoveryRequest Err(DiscoveryError.Unavailable)
                    session = opened
                    saveFilters(values)
                    mutableState.value = state.value.copy(definitions = opened.filters, filters = values)
                }
                requireNotNull(session).loadMore()
            }
            if (token != serial || !active) return@launch
            result.onOk { mutableState.value = state.value.copy(books = it.books, loading = false,
                loaded = true, hasMore = it.nextCursor != null) }
                .onErr { mutableState.value = state.value.copy(loading = false, error = it) }
        }
    }
}
