package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryVersion
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.version
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class SearchHubBook(val id: String, val information: Flow<Result<BookInformation, WebRequestError>>)
data class SearchHubSource(
    val id: Identifier, val name: String, val books: List<SearchHubBook> = emptyList(),
    val loading: Boolean = false, val error: Boolean = false, val searched: Boolean = false,
)
data class SearchHubState(
    val query: String = "", val submittedKeyword: String = "", val history: List<String> = emptyList(),
    val selected: Identifier? = null, val sources: List<SearchHubSource> = emptyList(),
) {
    val aggregate get() = selected == null
}

@HiltViewModel
class SearchHubViewModel internal constructor(
    private val registry: WebSourceRegistry,
    private val explore: ExploreRepository,
    private val books: BookRepository,
    users: UserDataRepository,
    private val accounts: SourceSessionManager,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(registry: WebSourceRegistry, explore: ExploreRepository, books: BookRepository,
        users: UserDataRepository, accounts: SourceSessionManager) : this(registry, explore, books, users, accounts, Dispatchers.IO)

    private val history = users.stringListUserData(UserDataPath.Search.History.path)
    private val mutable = MutableStateFlow(SearchHubState())
    val state = mutable.asStateFlow()
    private var versions = emptyMap<Identifier, DiscoveryVersion>()
    private var work: Job? = null
    private var epoch = 0L
    private var active = false
    // Shared by submissions: cancelled work releases its permit before a replacement starts.
    private val gate = Semaphore(4)

    init {
        viewModelScope.launch { history.getFlow().collect { values -> mutable.value = state.value.copy(history = values.orEmpty().reversed()) } }
        viewModelScope.launch {
            combine(registry.sources, accounts.changes) { sources, generations ->
                // Registry membership is enablement. Registered sources initialize lazily on first use.
                sources.filter { SourceCapability.Search in it.metadata.capabilities }
                    .associate { it.metadata.id to it.version(generations) }
            }.distinctUntilChanged().collect { next ->
                cancelWork()
                val old = state.value
                val selected = old.selected?.takeIf { it in next }
                val changedScope = selected != old.selected
                val sources = next.map { (id, version) ->
                    old.sources.firstOrNull { it.id == id && versions[id] == version && !changedScope }
                        ?: SearchHubSource(id, version.metadata.item.name)
                }
                versions = next
                mutable.value = state.value.copy(sources = sources, selected = selected)
                loadPending()
            }
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) loadPending() else cancelWork()
    }

    fun select(id: Identifier?) {
        if (id == state.value.selected || id != null && id !in versions) return
        cancelWork()
        mutable.value = state.value.copy(selected = id, sources = state.value.sources.map { SearchHubSource(it.id, it.name) })
        loadPending()
    }

    fun setQuery(value: String) {
        if (value == state.value.query) return
        cancelWork()
        mutable.value = state.value.copy(query = value, submittedKeyword = "",
            sources = state.value.sources.map { SearchHubSource(it.id, it.name) })
    }

    fun search(value: String = state.value.query) {
        val keyword = value.trim()
        if (keyword.isEmpty()) return
        cancelWork()
        mutable.value = state.value.copy(query = keyword, submittedKeyword = keyword,
            sources = state.value.sources.map { SearchHubSource(it.id, it.name) })
        viewModelScope.launch(io) { history.update { old -> old.filterNot { it == keyword } + keyword } }
        loadPending()
    }

    private fun cancelWork() {
        epoch++
        work?.cancel()
        work = null
        mutable.value = state.value.copy(sources = state.value.sources.map {
            if (it.loading) it.copy(loading = false, searched = false) else it
        })
    }

    private fun loadPending() {
        val snapshot = state.value
        if (!active || snapshot.submittedKeyword.isBlank() || work?.isActive == true) return
        val targets = snapshot.sources.filter { (snapshot.aggregate || it.id == snapshot.selected) && !it.searched }
        if (targets.isEmpty()) return
        val token = ++epoch
        val sourceVersions = versions
        val ids = targets.map { it.id }.toSet()
        mutable.value = snapshot.copy(sources = snapshot.sources.map { if (it.id in ids) it.copy(loading = true) else it })
        work = viewModelScope.launch {
            supervisorScope {
                targets.forEach { target -> launch {
                    gate.withPermit { load(target.id, sourceVersions.getValue(target.id), snapshot.submittedKeyword,
                        if (snapshot.aggregate) 6 else Int.MAX_VALUE, token) }
                } }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun load(id: Identifier, version: DiscoveryVersion, keyword: String, limit: Int, token: Long) {
        val results = linkedMapOf<String, SearchHubBook>()
        var failed = false
        try {
            withTimeout(30_000) {
                flow {
                    val session = explore.open(id).getOrElse { throw IllegalStateException("Source search unavailable") }
                    emitAll(session.search(session.types.first(), keyword))
                }.flowOn(io).buffer(0).transformWhile { event ->
                    emit(event)
                    event !is SearchResult.End && event !is SearchResult.Empty
                }.collect { event ->
                    val bookId = when (event) {
                        is SearchResult.MultipleBook -> event.bookId
                        is SearchResult.SingleBook -> event.bookId
                        else -> null
                    }
                    if (bookId != null && bookId !in results) {
                        val info = (event as? SearchResult.MultipleBook)?.information
                        results[bookId] = SearchHubBook(bookId, info?.let { flowOf(Ok(it)) }
                            ?: books.getBookInformationFlow(bookId))
                    }
                    failed = failed || event is SearchResult.Error
                    publish(id, version, token) { it.copy(books = results.values.toList(), error = failed) }
                    if (results.size >= limit) throw PreviewComplete()
                }
            }
        } catch (_: PreviewComplete) {
            // Stop upstream pagination after the aggregate preview, retaining collected results.
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            failed = true
        }
        publish(id, version, token) { it.copy(books = results.values.toList(), loading = false, searched = true, error = failed) }
    }

    private fun publish(id: Identifier, version: DiscoveryVersion, token: Long, update: (SearchHubSource) -> SearchHubSource) {
        if (!active || token != epoch || registry.sources.value.firstOrNull { it.metadata.id == id }
                ?.version(accounts.changes.value) != version) return
        mutable.value = state.value.copy(sources = state.value.sources.map { if (it.id == id) update(it) else it })
    }

    fun deleteHistory(value: String) { viewModelScope.launch(io) { history.update { it.filterNot { item -> item == value } } } }
    fun clearHistory() { viewModelScope.launch(io) { history.update { emptyList() } } }
    private class PreviewComplete : RuntimeException()
}
