package indi.renakoni.nextvol.ui.storagemanager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import indi.renakoni.nextvol.data.storage.StorageUsageSnapshot

interface StorageManagerUiState {
    val isLoading: Boolean
    val failed: Boolean
    val snapshot: StorageUsageSnapshot?
    val load: () -> Unit
}

class MutableStorageManagerUiState : StorageManagerUiState {
    override var load: () -> Unit = {}
    override var isLoading by mutableStateOf(true)
    override var failed by mutableStateOf(false)
    override var snapshot by mutableStateOf<StorageUsageSnapshot?>(null)
}
