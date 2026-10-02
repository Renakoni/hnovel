package indi.renakoni.nextvol.ui.storagemanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.storage.StorageUsageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class StorageManagerViewModel @Inject constructor(
    private val storageUsageRepository: StorageUsageRepository,
    private val downloads: BookDownloadStore,
    private val workManager: WorkManager,
    private val downloadProgress: DownloadProgressRepository,
) : ViewModel() {
    val uiState = MutableStorageManagerUiState().apply {
        load = ::load
    }
    private val refreshLock = Mutex()

    init {
        viewModelScope.launch {
            try { uiState.snapshot = storageUsageRepository.getCachedSnapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A fresh scan can recover an unavailable snapshot. */ }
            refresh()
        }
    }

    private var entered = false

    fun onResume() {
        if (entered) load()
        entered = true
    }

    fun load() {
        if (!refreshLock.isLocked) viewModelScope.launch { refresh() }
    }

    suspend fun clearReadingCache(): Boolean {
        val cleared = downloads.clearReadingCache()
        if (cleared) refresh()
        return cleared
    }

    suspend fun clearDownloads() = withContext(Dispatchers.IO) {
        val generation = downloads.generation()
        try { downloads.clearDownloads() }
        finally {
            try { workManager.cancelAllWorkByTag(CacheBookWork.generationTag(generation)).await() }
            finally { downloadProgress.clearCachedItems() }
        }
        refresh()
    }

    private suspend fun refresh() = refreshLock.withLock {
        withContext(Dispatchers.Main) {
            uiState.isLoading = true
            uiState.failed = false
        }
        try {
            val snapshot = storageUsageRepository.refreshSnapshot()
            withContext(Dispatchers.Main) { uiState.snapshot = snapshot }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { withContext(Dispatchers.Main) { uiState.failed = true } }
        finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { uiState.isLoading = false } }
    }
}
