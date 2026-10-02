package indi.renakoni.nextvol.ui.storagemanager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.storage.StorageUsageSnapshot
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun StorageCleanupSettings(
    snapshot: StorageUsageSnapshot,
    clearReadingCache: suspend () -> Boolean,
    clearDownloads: suspend () -> Unit,
    onOpenBooks: () -> Unit,
    enabled: Boolean,
) {
    val resources = LocalResources.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var deleteDownloads by remember { mutableStateOf<Boolean?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    if (deleteDownloads != null) AlertDialog(
        onDismissRequest = { if (!clearing) deleteDownloads = null },
        icon = { Icon(painterResource(if (deleteDownloads == true) R.drawable.delete_forever_24px else R.drawable.database_24px), null) },
        title = { Text(stringResource(if (deleteDownloads == true) R.string.storage_delete_all else R.string.settings_clear_reading_cache)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (deleteDownloads == true) R.string.storage_delete_all_confirm else R.string.storage_clear_cache_confirm))
                Text(stringResource(R.string.storage_content_estimate, formatSize(
                    if (deleteDownloads == true) snapshot.downloadBytes else snapshot.readingCacheBytes)),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = { TextButton(enabled = !clearing, onClick = {
            val remove = deleteDownloads == true
            clearing = true
            scope.launch {
                val message = try {
                    if (remove) { clearDownloads(); R.string.storage_downloads_deleted }
                    else if (clearReadingCache()) R.string.settings_cache_cleared else R.string.storage_cleanup_busy
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { R.string.settings_cache_clear_failed }
                finally { clearing = false; deleteDownloads = null }
                snackbar.showSnackbar(resources.getString(message), withDismissAction = true)
            }
        }) { Text(stringResource(if (clearing) R.string.processing else if (deleteDownloads == true) R.string.storage_delete_all else R.string.storage_clear_cache),
            color = if (deleteDownloads == true && !clearing) colors.error else colors.primary) } },
        dismissButton = { TextButton(enabled = !clearing, onClick = { deleteDownloads = null }) { Text(stringResource(android.R.string.cancel)) } },
    )

    Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ContentSizeHeader(R.drawable.database_24px, R.string.storage_reading_cache, snapshot.readingCacheBytes)
            Text(stringResource(if (snapshot.readingCacheBytes == 0L) R.string.storage_cache_empty else R.string.storage_reading_cache_desc),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            FilledTonalButton(onClick = { deleteDownloads = false }, modifier = Modifier.align(Alignment.End).testTag("clear_reading_cache"),
                enabled = enabled && !clearing && snapshot.readingCacheBytes > 0L) {
                Text(stringResource(R.string.storage_clear_cache))
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ContentSizeHeader(R.drawable.cloud_download_24px, R.string.storage_downloads, snapshot.downloadBytes)
            Text(if (snapshot.downloadedBookCount == 0) stringResource(R.string.storage_downloads_empty)
                else stringResource(R.string.storage_download_counts, snapshot.downloadedBookCount, snapshot.downloadedChapterCount),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (snapshot.preparingChapterCount > 0) Text(stringResource(R.string.storage_preparing_chapters, snapshot.preparingChapterCount),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (snapshot.unfinishedDownloadCount > 0) Text(stringResource(R.string.storage_unfinished_downloads, snapshot.unfinishedDownloadCount),
                style = MaterialTheme.typography.bodySmall, color = colors.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.check_24px), null, Modifier.size(16.dp), tint = colors.primary)
                Text(stringResource(R.string.storage_downloads_protected), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onOpenBooks, modifier = Modifier.testTag("manage_stored_books"), enabled = !clearing) {
                    Text(stringResource(R.string.storage_manage_books))
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.arrow_forward_24px), null, Modifier.size(16.dp))
                }
                TextButton(onClick = { deleteDownloads = true }, modifier = Modifier.testTag("delete_all_downloads"),
                    enabled = enabled && !clearing && (snapshot.downloadBytes > 0L || snapshot.unfinishedDownloadCount > 0)) {
                    Text(stringResource(R.string.storage_delete_all), color = if ((snapshot.downloadBytes > 0L || snapshot.unfinishedDownloadCount > 0) && enabled && !clearing) colors.error else colors.onSurface.copy(alpha = .38f))
                }
            }
        }
    }
}

@Composable
private fun ContentSizeHeader(icon: Int, title: Int, size: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(painterResource(icon), null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(formatSize(size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(formatSize(size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
