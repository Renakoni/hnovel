package indi.renakoni.nextvol.ui.storagemanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R

@Composable
fun StorageOverviewContent(
    modifier: Modifier,
    uiState: StorageManagerUiState,
    clearReadingCache: suspend () -> Boolean,
    clearDownloads: suspend () -> Unit,
    onOpenBooks: () -> Unit,
) {
    val snapshot = uiState.snapshot
    LazyColumn(modifier.testTag("storage_overview"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (uiState.failed) item {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.storage_refresh_failed), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(uiState.load, enabled = !uiState.isLoading) { Text(stringResource(R.string.action_refresh)) }
                }
            }
        }
        if (snapshot == null) {
            if (uiState.isLoading) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.storage_measuring), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            item { StorageUsageChart(snapshot, uiState.isLoading) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.storage_your_content), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 4.dp))
                    StorageCleanupSettings(snapshot, clearReadingCache, clearDownloads, onOpenBooks, !uiState.isLoading)
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}
