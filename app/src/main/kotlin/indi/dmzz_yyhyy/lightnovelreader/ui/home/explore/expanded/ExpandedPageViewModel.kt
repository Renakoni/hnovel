package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.MutableExploreUiState
import io.nightfish.lightnovelreader.api.identifier.Identifier
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ExpandedPageViewModel @Inject constructor(
    private val sourceRegistry: WebSourceRegistry,
    private val sourceProvider: WebBookDataSourceProvider,
    private val bookshelfRepository: BookshelfRepository,
    private val textProcessingRepository: TextProcessingRepository,
    private val bookRepository: BookRepository
) : ViewModel() {
    private var expandedPageDataSource: ExploreExpandedPageDataSource? = null
    private var exploreExpandedPageBookListCollectJob: Job? = null
    private var lastExpandedPageDataSourceId: String = ""
    private val _uiState = MutableExpandedPageUiState()
    val uiState: ExpandedPageUiState = _uiState

    val exploreUiState = MutableExploreUiState()
    private var sourceId: Identifier? = null
    private var initialization: Job? = null

    init {
        viewModelScope.launch {
            bookshelfRepository.getAllBookshelfBookIdsFlow().collect { ids ->
                _uiState.allBookshelfBookIds = ids.toList()
            }
        }
    }

    fun init(expandedPageDataSourceId: String, sourceBookKey: String? = null) {
        if (expandedPageDataSourceId == lastExpandedPageDataSourceId) return
        lastExpandedPageDataSourceId = expandedPageDataSourceId
        // Legacy browsing ingress captures the selection once. Book tags supply their source.
        val id = sourceId ?: sourceBookKey?.let { BookIdentity.book(it).sourceId }
            ?: sourceProvider.value.id
        sourceId = id
        initialization?.cancel()
        initialization = viewModelScope.launch {
            exploreUiState.isRefreshing = true
            try {
                val resolution = sourceRegistry.resolve(id)
                val runtime = (resolution as? SourceResolution.Ready)?.runtime
                val provider = runtime?.explorePages as? ExplorePageProvider.DefaultExplorePageProvider
                expandedPageDataSource = provider?.exploreExpandedPageDataSourceMap?.get(expandedPageDataSourceId)
                exploreUiState.isOffLine = expandedPageDataSource == null
                expandedPageDataSource?.let { dataSource ->
                    _uiState.pageTitle = withContext(Dispatchers.IO) {
                        textProcessingRepository.processText { dataSource.title }
                    }
                    _uiState.filters.clear()
                    _uiState.filters.addAll(dataSource.filters)
                    loadBookResult()
                }
            } finally {
                exploreUiState.isRefreshing = false
            }
        }
    }

    fun loadBookResult() {
        _uiState.bookList.clear()
        exploreExpandedPageBookListCollectJob?.cancel()
        exploreExpandedPageBookListCollectJob = viewModelScope.launch(Dispatchers.IO) {
            expandedPageDataSource?.let { dataSource ->
                dataSource.getResultFlow().collect { rawResult ->
                    when(rawResult) {
                        is SearchResult.SingleBook -> addBook(rawResult.bookId)
                        is SearchResult.MultipleBook -> addBook(rawResult.bookId)
                        else -> {}
                    }
                }
            }
        }
    }

    private fun addBook(remoteId: String) {
        val book = SourceBookId(requireNotNull(sourceId), remoteId)
        _uiState.bookList.add(book.storageKey to bookRepository.getBookInformationFlow(book))
    }

    fun loadMore() {
        expandedPageDataSource?.loadMore()
    }

    fun clear() {
        lastExpandedPageDataSourceId = ""
    }

    fun refresh() {
        val page = lastExpandedPageDataSourceId
        lastExpandedPageDataSourceId = ""
        init(page)
    }
}
