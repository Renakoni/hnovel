package indi.renakoni.nextvol.ui.storagemanager

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun StorageCleanupSettings(
    clearReadingCache: suspend () -> Unit,
    clearDownloads: suspend () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearDownloadsSelected by remember { mutableStateOf<Boolean?>(null) }
    var clearingCache by remember { mutableStateOf(false) }

    if (clearDownloadsSelected != null) AlertDialog(
        onDismissRequest = { if (!clearingCache) clearDownloadsSelected = null },
        title = { Text(stringResource(if (clearDownloadsSelected == true) R.string.settings_clear_downloads else R.string.settings_clear_reading_cache)) },
        text = { Text(stringResource(if (clearDownloadsSelected == true) R.string.settings_clear_downloads_desc else R.string.settings_clear_reading_cache_desc)) },
        confirmButton = { TextButton(enabled = !clearingCache, onClick = {
            clearingCache = true
            scope.launch {
                try {
                    if (clearDownloadsSelected == true) clearDownloads() else clearReadingCache()
                    clearDownloadsSelected = null
                    Toast.makeText(context, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, R.string.settings_cache_clear_failed, Toast.LENGTH_SHORT).show() }
                finally { clearingCache = false }
            }
        }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(enabled = !clearingCache, onClick = { clearDownloadsSelected = null }) {
            Text(stringResource(android.R.string.cancel))
        } }
    )

    SettingsCategory {
        SettingsClickableEntry(
            modifier = Modifier.background(colorScheme.surfaceContainer),
            painter = painterResource(R.drawable.database_24px),
            title = stringResource(R.string.settings_clear_reading_cache),
            description = stringResource(R.string.settings_clear_reading_cache_desc),
            onClick = { clearDownloadsSelected = false }
        )
        SettingsClickableEntry(
            modifier = Modifier.background(colorScheme.surfaceContainer),
            painter = painterResource(R.drawable.cloud_download_24px),
            title = stringResource(R.string.settings_clear_downloads),
            description = stringResource(R.string.settings_clear_downloads_desc),
            onClick = { clearDownloadsSelected = true }
        )
    }
}
