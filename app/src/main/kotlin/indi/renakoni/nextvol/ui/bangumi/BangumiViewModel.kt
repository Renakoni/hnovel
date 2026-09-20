package indi.renakoni.nextvol.ui.bangumi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bangumi.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class BangumiUiState(
    val account: BangumiAccountState = BangumiAccountState(),
    val bindings: List<BangumiBindingEntity> = emptyList(),
    val book: BookInformation? = null,
    val query: String = "",
    val candidates: List<BangumiCandidate> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val hasMore: Boolean = false,
    val busy: Boolean = false,
    val preview: BangumiBookPreview? = null,
    val mapping: List<BangumiVolumeMapping> = emptyList(),
    val baseline: Set<String> = emptySet(),
    val privateCollection: Boolean = true,
    val error: Int? = null,
)

@HiltViewModel
class BangumiViewModel @Inject constructor(private val repository: BangumiRepository, savedStateHandle: SavedStateHandle) : ViewModel() {
    val bookId = savedStateHandle.toRoute<BangumiRoute>().bookId
    private val mutableState = MutableStateFlow(BangumiUiState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var searchVersion = 0
    private var offset = 0

    init {
        viewModelScope.launch {
            repository.accounts.load()
            combine(repository.accounts.state, repository.bindings) { account, bindings ->
                account to bindings.filter { it.accountId == account.user?.id }
            }.collect { (account, bindings) ->
                mutableState.update { old -> old.copy(account = account, bindings = bindings,
                    preview = old.preview.takeIf { account.user?.id == it?.accountId }) }
            }
        }
        if (bookId != null) work {
            val book = repository.localBook(bookId)
            mutableState.update { it.copy(book = book, query = book.title) }
            search()
        }
    }

    fun connect(token: String) = work { repository.connect(token.trim()) }
    fun disconnect() = work { repository.accounts.disconnect() }
    fun unlink(id: String) = work { repository.unlink(id) }
    fun retry(id: String) = work { repository.retry(id) }

    fun query(value: String) {
        searchJob?.cancel()
        searchVersion++
        offset = 0
        mutableState.update { it.copy(query = value, searching = false, searched = false, candidates = emptyList(), hasMore = false, error = null) }
    }

    fun search(more: Boolean = false) {
        val id = bookId ?: return
        val query = state.value.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        val version = ++searchVersion
        if (!more) offset = 0
        mutableState.update { it.copy(searching = true, error = null, candidates = if (more) it.candidates else emptyList()) }
        searchJob = viewModelScope.launch {
            try {
                val (candidates, hasMore) = repository.search(id, query, offset)
                if (version != searchVersion) return@launch
                offset += 10
                mutableState.update { it.copy(candidates = (it.candidates + candidates).distinctBy { item -> item.subject.id },
                    hasMore = hasMore, searched = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (version == searchVersion) mutableState.update { it.copy(error = error(failure)) } }
            finally { if (version == searchVersion) mutableState.update { it.copy(searching = false) } }
        }
    }

    fun choose(subjectId: Int) = work {
        val id = bookId ?: return@work
        val preview = repository.preview(id, subjectId)
        val old = state.value.bindings.find { it.bookId == id && it.subjectId == subjectId }?.binding()
        val baseline = old?.baseline?.intersect(preview.mapping.mapNotNull { it.editionKey }.toSet())
            ?: preview.mapping.mapNotNull { it.editionKey }.distinct().take(preview.remote?.volumes ?: 0).toSet()
        mutableState.update { it.copy(preview = preview, mapping = preview.mapping, baseline = baseline,
            privateCollection = preview.remote?.private ?: true) }
    }

    fun mapping(volumeId: String, editionKey: String?) {
        if (state.value.busy) return
        mutableState.update { old ->
            val rows = old.mapping.map { if (it.volumeId == volumeId) it.copy(editionKey = editionKey) else it }
            old.copy(mapping = rows, baseline = old.baseline.intersect(rows.mapNotNull { it.editionKey }.toSet()))
        }
    }
    fun complete(volumeId: String, checked: Boolean) {
        if (!state.value.busy) mutableState.update { old -> old.copy(mapping = old.mapping.map { if (it.volumeId == volumeId) it.copy(complete = checked) else it }) }
    }
    fun baseline(key: String, checked: Boolean) {
        if (!state.value.busy) mutableState.update { it.copy(baseline = if (checked) it.baseline + key else it.baseline - key) }
    }
    fun privateCollection(checked: Boolean) { mutableState.update { it.copy(privateCollection = checked) } }
    fun dismissPreview() { if (!state.value.busy) mutableState.update { it.copy(preview = null, error = null) } }

    fun confirm() = work {
        val current = state.value
        val preview = current.preview ?: return@work
        repository.bind(preview, current.mapping, current.baseline, current.privateCollection)
        mutableState.update { it.copy(preview = null) }
    }

    private fun work(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = error(failure)) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun error(failure: Exception) = when {
        failure is BangumiApiException && failure.status == 401 -> R.string.bangumi_error_auth
        failure is java.io.IOException -> R.string.bangumi_error_network
        else -> R.string.bangumi_error_operation
    }
}
