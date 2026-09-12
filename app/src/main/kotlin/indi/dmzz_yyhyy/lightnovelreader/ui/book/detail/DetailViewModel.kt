package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.michaelbull.result.onOk
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadProgressRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.work.ExportBookToEPUBWork
import indi.dmzz_yyhyy.lightnovelreader.data.book.observeSubmittedUniqueWork
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val bookshelfRepository: BookshelfRepository,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val workManager: WorkManager
) : ViewModel() {
    private val _uiState = MutableDetailUiState()
    var exportSettings = ExportSettings()
    private var book: indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId? = null
    private var informationJob: Job? = null
    val uiState: DetailUiState = _uiState

    var isInitialized by mutableStateOf(false)
        private set

    fun init(bookId: String) {
        Log.d("DetailViewModel", "Init bookId = $bookId")
        if (isInitialized) return
        book = indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity.book(bookId)
        isInitialized = true
        loadInformation(bookId)
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.readingAvailability(bookId).collectLatest { availability ->
                _uiState.readingAvailable = availability.available
                _uiState.canCache = availability.online
                _uiState.metadataOnly = availability.metadataOnly
                if (availability.available) bookRepository.getBookVolumesFlow(bookId, WebDataSourcePriority.High).collect {
                    _uiState.bookVolumes = it
                } else _uiState.bookVolumes = null
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.getUserReadingDataFlow(bookId).collect {
                _uiState.userReadingData = it
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.isCached = bookRepository.getIsBookCached(bookId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            bookshelfRepository.getBookshelfBookMetadataFlow(bookId).collect {
                _uiState.isInBookshelf = it != null
            }
        }
        viewModelScope.launch {
            snapshotFlow { downloadProgressRepository.downloadItemIdList }.collect {
                _uiState.downloadItem = downloadProgressRepository.downloadItemIdList.findLast { it.bookId == bookId && it.type == DownloadType.CACHE }
            }
        }
    }

    fun retryInformation() { book?.let { loadInformation(it.storageKey) } }

    private fun loadInformation(bookId: String) {
        informationJob?.cancel()
        informationJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.bookInformation = null
            bookRepository.getBookInformationFlow(bookId, WebDataSourcePriority.High).collect { result ->
                result.onOk {
                    val metadata = bookshelfRepository.getBookshelfBookMetadata(bookId) ?: return@onOk
                    metadata.bookShelfIds.forEach { shelf -> bookshelfRepository.deleteBookFromBookshelfUpdatedBookIds(shelf, bookId) }
                    bookshelfRepository.updateBookshelfBookMetadataLastUpdateTime(bookId, it.lastUpdated)
                }
                _uiState.bookInformation = result
            }
        }
    }

    fun cacheBook(bookId: String): Flow<WorkInfo?> {
        if (!_uiState.canCache) return flowOf(null)
        val isCachedFlow = bookRepository.cacheBook(bookId)
        viewModelScope.launch(Dispatchers.IO) {
            isCachedFlow.collect { workInfo ->
                if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                    _uiState.isCached = bookRepository.getIsBookCached(bookId)
                }
            }
        }
        return isCachedFlow
    }

    suspend fun tagPage(tag: String) = book?.let { bookRepository.bookTagPage(it, tag) }


    fun exportToEpub(uri: Uri, bookId: String, title: String): Flow<WorkInfo?> {
        if (!_uiState.readingAvailable) return flowOf(null)
        val key = indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity.bookKey(bookId)
        val workRequest = OneTimeWorkRequestBuilder<ExportBookToEPUBWork>()
            .setInputData(
                workDataOf(
                    "bookId" to key,
                    "uri" to uri.toString(),
                    "title" to title,
                    "includeImages" to exportSettings.includeImages,
                    "exportType" to exportSettings.exportType.name,
                    "selectedVolume" to exportSettings.selectedVolumeIds.joinToString(",")
                )
            )
            .build()
        val operation = workManager.enqueueUniqueWork(
            ExportBookToEPUBWork.ofId(key),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        return workManager.observeSubmittedUniqueWork(ExportBookToEPUBWork.ofId(key), operation)
    }
}
