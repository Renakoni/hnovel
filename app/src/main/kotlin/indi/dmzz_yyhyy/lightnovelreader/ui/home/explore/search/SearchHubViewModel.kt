package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.getOrElse
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceCapability
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceStatus
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class SearchHubSource(val id: Identifier, val name: String, val books: List<String> = emptyList(), val loading: Boolean = false, val error: Boolean = false)
data class SearchHubState(val query: String = "", val history: List<String> = emptyList(), val selected: Identifier? = null, val aggregate: Boolean = true, val sources: List<SearchHubSource> = emptyList())

@HiltViewModel
class SearchHubViewModel @Inject constructor(
    private val registry: WebSourceRegistry,
    private val explore: ExploreRepository,
    private val books: BookRepository,
    users: UserDataRepository,
) : ViewModel() {
    private val history = users.stringListUserData(UserDataPath.Search.History.path)
    private val mutable = MutableStateFlow(SearchHubState())
    val state: StateFlow<SearchHubState> = mutable.asStateFlow()
    private var work: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { history.getFlow().collect { values -> mutable.update { it.copy(history = values.orEmpty().reversed()) } } }
        viewModelScope.launch { registry.sources.collect { refreshSources() } }
    }

    fun refreshSources() {
        val sources = registry.sources.value.filter { it.status == SourceStatus.Ready && SourceCapability.Search in it.metadata.capabilities }
            .map { SearchHubSource(it.metadata.id, it.metadata.item.name) }
        mutable.update { it.copy(sources = sources, selected = it.selected?.takeIf { id -> sources.any { s -> s.id == id } }) }
    }

    fun select(id: Identifier?) { work?.cancel(); mutable.update { it.copy(selected = id, aggregate = id == null, sources = it.sources.map { s -> s.copy(books = emptyList(), loading = false, error = false) }) } }
    fun setQuery(value: String) { mutable.update { it.copy(query = value) } }
    fun search(value: String = state.value.query) {
        if (value.isBlank()) return
        setQuery(value); viewModelScope.launch(Dispatchers.IO) { history.update { old -> old.filterNot { it == value } + value } }
        work?.cancel(); work = viewModelScope.launch(Dispatchers.IO) {
            val selected = state.value.selected
            val targets = state.value.sources.filter { selected == null || it.id == selected }
            mutable.update { it.copy(sources = it.sources.map { s -> if (targets.any { t -> t.id == s.id }) s.copy(books = emptyList(), loading = true, error = false) else s }) }
            val gate = Semaphore(4)
            targets.map { target -> async { gate.withPermit { load(target, value) } } }.awaitAll()
        }
    }
    private suspend fun load(target: SearchHubSource, keyword: String) {
        try {
            val session = explore.open(target.id).getOrElse { throw IllegalStateException() }
            val type = session.types.firstOrNull() ?: throw IllegalStateException()
            val result = mutableListOf<String>()
            session.search(type, keyword).collect { event ->
                if (event is SearchResult.MultipleBook && result.size < 6) result += event.bookId
                if (event is SearchResult.End || result.size >= 6) throw StopSearch
            }
            mutable.update { it.copy(sources = it.sources.map { s -> if (s.id == target.id) s.copy(books = result, loading = false) else s }) }
        } catch (_: StopSearch) {
            mutable.update { it.copy(sources = it.sources.map { s -> if (s.id == target.id) s.copy(loading = false) else s }) }
        } catch (_: Exception) {
            mutable.update { it.copy(sources = it.sources.map { s -> if (s.id == target.id) s.copy(loading = false, error = true) else s }) }
        }
    }
    fun deleteHistory(value: String) { viewModelScope.launch(Dispatchers.IO) { history.update { it.filterNot { item -> item == value } } } }
    fun clearHistory() { viewModelScope.launch(Dispatchers.IO) { history.update { emptyList() } } }
    private object StopSearch : Throwable()
}
