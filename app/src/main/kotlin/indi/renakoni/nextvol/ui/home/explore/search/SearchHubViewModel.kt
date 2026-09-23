package indi.renakoni.nextvol.ui.home.explore.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.explore.*
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryVersion
import indi.renakoni.nextvol.ui.home.discovery.version
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class SearchHubBook(
    val id: String, val source: Identifier, val sourceName: String,
    val preview: BookInformation?, val information: Flow<Result<BookInformation, WebRequestError>>,
)

data class SearchHubSource(
    val id: Identifier, val name: String, val category: SourceCategory?,
    val page: Int = 1, val nextPage: Int? = null, val pending: Boolean = true,
    val failure: SourceSearchFailure? = null,
)

data class SearchHubState(
    val query: String = "", val submittedKeyword: String = "", val history: List<String> = emptyList(),
    val scope: SourceCategory? = null, val sources: List<SearchHubSource> = emptyList(),
    val books: List<SearchHubBook> = emptyList(), val searching: Boolean = false,
    val stopped: Boolean = false, val limited: Boolean = false,
    val completed: Int = 0, val total: Int = 0, val revision: Long = 0,
) {
    val scopedSources get() = sources.filter { scope == null || it.category == scope }
    val failures get() = scopedSources.filter { it.failure != null }
    val hasMore get() = !limited && scopedSources.any { it.nextPage != null }
}

@HiltViewModel
class SearchHubViewModel internal constructor(
    private val registry: WebSourceRegistry,
    private val coordinator: SearchCoordinator,
    private val books: BookRepository,
    users: UserDataRepository,
    private val accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
    browsing: SourceBrowseSettings,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(registry: WebSourceRegistry, coordinator: SearchCoordinator, books: BookRepository,
        users: UserDataRepository, accounts: SourceSessionManager, saved: SavedStateHandle,
        browsing: SourceBrowseSettings) : this(registry, coordinator, books, users, accounts, saved, browsing, Dispatchers.IO)

    private val history = users.stringListUserData(UserDataPath.Search.History.path)
    private val mutable = MutableStateFlow(SearchHubState(
        query = saved["search.query"] ?: "", submittedKeyword = saved["search.submitted"] ?: "",
        scope = if (saved.contains("search.scope")) SourceCategory.entries.find { it.name == saved.get<String>("search.scope") }
            else browsing.scope.value,
    ))
    val state = mutable.asStateFlow()
    private var versions = emptyMap<Identifier, DiscoveryVersion>()
    private val results = linkedMapOf<String, SearchHubBook>()
    private var work: Job? = null
    private var epoch = 0L
    private var active = false
    private val detailsGate = Semaphore(4)

    init {
        saved["search.scope"] = state.value.scope?.name.orEmpty()
        viewModelScope.launch {
            history.getFlow().collect { values ->
                mutable.value = state.value.copy(history = values.orEmpty().asReversed().filter(String::isNotBlank).distinct())
            }
        }
        viewModelScope.launch {
            combine(registry.sources, accounts.changes) { sources, generations ->
                sources.filter { SourceCapability.Search in it.metadata.capabilities }
                    .associate { it.metadata.id to it.version(generations) }
            }.distinctUntilChanged().collect { next ->
                cancelWork()
                val old = state.value.sources.associateBy { it.id }
                val unchanged = next.keys.filter { next[it] == versions[it] }.toSet()
                results.entries.removeAll { it.value.source !in unchanged }
                val sources = next.map { (id, version) ->
                    old[id]?.takeIf { id in unchanged } ?: SearchHubSource(id, version.metadata.item.name, version.metadata.category)
                }
                versions = next
                mutable.value = state.value.copy(sources = sources, books = rankedResults())
                loadPending()
            }
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) loadPending() else cancelWork()
    }

    fun selectScope(scope: SourceCategory?) {
        if (scope == state.value.scope) return
        saved["search.scope"] = scope?.name.orEmpty()
        reset(state.value.copy(scope = scope))
        loadPending()
    }

    fun setQuery(value: String) {
        if (value == state.value.query) return
        saved["search.query"] = value
        saved["search.submitted"] = ""
        reset(state.value.copy(query = value, submittedKeyword = ""))
    }

    fun search(value: String = state.value.query) {
        val keyword = value.trim()
        if (keyword.isEmpty()) return
        saved["search.query"] = keyword
        saved["search.submitted"] = keyword
        reset(state.value.copy(query = keyword, submittedKeyword = keyword))
        viewModelScope.launch(io) { history.update { old -> old.filterNot { it == keyword } + keyword } }
        loadPending()
    }

    private fun reset(next: SearchHubState) {
        cancelWork()
        results.clear()
        mutable.value = next.copy(books = emptyList(), sources = next.sources.map { SearchHubSource(it.id, it.name, it.category) },
            searching = false, stopped = false, limited = false, completed = 0, total = 0, revision = next.revision + 1)
    }

    fun stop() {
        cancelWork()
        mutable.value = state.value.copy(stopped = true)
    }

    fun resume() {
        mutable.value = state.value.copy(stopped = false)
        loadPending()
    }

    fun loadMore() {
        if (state.value.searching || !state.value.hasMore || state.value.stopped) return
        mutable.value = state.value.copy(sources = state.value.sources.map {
            if (it.nextPage != null && it.failure == null) it.copy(page = it.nextPage, nextPage = null, pending = true) else it
        })
        loadPending()
    }

    fun retryFailures() {
        if (state.value.searching) return
        mutable.value = state.value.copy(stopped = false, sources = state.value.sources.map {
            if (it.failure != null) it.copy(pending = true, failure = null) else it
        })
        loadPending()
    }

    private fun cancelWork() {
        epoch++
        work?.cancel()
        work = null
        mutable.value = state.value.copy(searching = false)
    }

    private fun loadPending() {
        val snapshot = state.value
        if (!active || snapshot.stopped || snapshot.submittedKeyword.isBlank() || work?.isActive == true) return
        val targets = snapshot.scopedSources.filter { it.pending }
        if (targets.isEmpty()) return
        val token = ++epoch
        val sourceVersions = versions
        mutable.value = snapshot.copy(searching = true,
            completed = snapshot.scopedSources.size - targets.size, total = snapshot.scopedSources.size)
        work = viewModelScope.launch {
            try {
                coordinator.search(snapshot.submittedKeyword, targets.map { SearchRequest(it.id, it.page) }).collect { batch ->
                    val id = batch.request.source
                    if (!active || token != epoch || registry.sources.value.firstOrNull { it.metadata.id == id }
                            ?.version(accounts.changes.value) != sourceVersions[id]) return@collect
                    accept(batch)
                }
            } finally {
                if (token == epoch) mutable.value = state.value.copy(searching = false)
            }
        }
    }

    private fun accept(batch: SearchBatch) {
        val source = state.value.sources.first { it.id == batch.request.source }
        var added = false
        batch.books.forEach { item ->
            if (item.bookId !in results) {
                added = true
                results[item.bookId] = SearchHubBook(item.bookId, source.id, source.name, item.information,
                    item.information?.let { flowOf(Ok(it)) } ?: details(item.bookId))
            }
        }
        val ranked = rankedResults()
        val limited = state.value.limited || batch.limited || ranked.size > SEARCH_RESULT_LIMIT
        if (ranked.size > SEARCH_RESULT_LIMIT) {
            val retained = ranked.take(SEARCH_RESULT_LIMIT).mapTo(hashSetOf()) { it.id }
            results.keys.retainAll(retained)
        }
        mutable.value = state.value.copy(books = ranked.take(SEARCH_RESULT_LIMIT), limited = limited,
            completed = state.value.completed + if (batch.complete) 1 else 0,
            sources = state.value.sources.map {
                if (it.id == source.id && batch.complete) it.copy(pending = false, failure = batch.failure,
                    nextPage = batch.nextPage?.takeIf { added && !limited }) else it
            })
    }

    private fun rankedResults() = results.values.sortedBy { book ->
        val info = book.preview
        val keyword = state.value.submittedKeyword
        when {
            info == null -> 3
            info.title.equals(keyword, ignoreCase = true) || info.author.equals(keyword, ignoreCase = true) -> 0
            info.title.contains(keyword, ignoreCase = true) || info.author.contains(keyword, ignoreCase = true) -> 1
            else -> 2
        }
    }

    private fun details(id: String): Flow<Result<BookInformation, WebRequestError>> {
        var cached: Result<BookInformation, WebRequestError>? = null
        return flow {
            cached?.let { emit(it) }
            if (cached?.isOk != true) detailsGate.withPermit {
                withTimeout(30_000) { books.getBookInformationFlow(id).collect { cached = it; emit(it) } }
            }
        }.catch { error ->
            currentCoroutineContext().ensureActive()
            // A local preview remains usable if its remote refresh times out or fails.
            if (cached?.isOk != true) {
                emit(com.github.michaelbull.result.Err(WebRequestError("Search", "Book information unavailable", error)))
            }
        }.flowOn(io)
    }

    fun deleteHistory(value: String) { viewModelScope.launch(io) { history.update { it.filterNot { item -> item == value } } } }
    fun clearHistory() { viewModelScope.launch(io) { history.update { emptyList() } } }
}
