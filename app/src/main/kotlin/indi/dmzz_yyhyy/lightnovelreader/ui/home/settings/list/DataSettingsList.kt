package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme.colorScheme
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
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.components.ImportUserDataDialog
import indi.dmzz_yyhyy.lightnovelreader.ui.components.SettingsClickableEntry
import indi.dmzz_yyhyy.lightnovelreader.ui.components.SettingsSwitchEntry
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.SettingState
import indi.dmzz_yyhyy.lightnovelreader.utils.uriLauncher
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.CancellationException

@Composable
fun DataSettingsList(
    onClickExportUserData: () -> Unit,
    settingState: SettingState,
    importData: (Uri, Boolean) -> OneTimeWorkRequest,
    onClickStorageManager: () -> Unit,
    clearReadingCache: suspend () -> Unit,
    clearDownloads: suspend () -> Unit,
) {
    val dataImportFailedText = stringResource(R.string.data_import_failed)
    val dataImportSuccessText = stringResource(R.string.data_import_success)

    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)
    val scope = rememberCoroutineScope()

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
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

    val startImport: (Uri, Boolean) -> Unit = { uri, overwrite ->
        isImporting = true
        scope.launch {
            workManager.getWorkInfoByIdFlow(importData(uri, overwrite).id).collect {
                when (it?.state) {
                    WorkInfo.State.FAILED -> {
                        isImporting = false
                        showImportDialog = false
                        pendingImportUri = null
                        Toast.makeText(context, dataImportFailedText, Toast.LENGTH_SHORT).show()
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        isImporting = false
                        showImportDialog = false
                        pendingImportUri = null
                        Toast.makeText(context, dataImportSuccessText, Toast.LENGTH_SHORT).show()
                    }
                    WorkInfo.State.CANCELLED -> {
                        isImporting = false
                        showImportDialog = false
                        pendingImportUri = null
                    }
                    else -> {}
                }
            }
        }
    }

    val importDataLauncher = uriLauncher { uri ->
        pendingImportUri = uri
        showImportDialog = true
    }

    if (showImportDialog && pendingImportUri != null) {
        val uri = pendingImportUri!!
        ImportUserDataDialog(
            isImporting = isImporting,
            onDismissRequest = {
                showImportDialog = false
                pendingImportUri = null
            },
            onClickMerge = { startImport(uri, false) },
            onClickOverwrite = { startImport(uri, true) },
        )
    }

    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.output_24px),
        title = stringResource(R.string.settings_snap_data),
        description = stringResource(R.string.settings_snap_data_desc),
        onClick = onClickExportUserData
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.input_24px),
        title = stringResource(R.string.settings_import_data),
        description = stringResource(R.string.settings_import_data_desc),
        onClick = { selectDataFile(importDataLauncher) }
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.database_24px),
        title = stringResource(R.string.settings_storage_manager),
        description = stringResource(R.string.settings_storage_manager_desc),
        onClick = onClickStorageManager
    )
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
    SettingsSwitchEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.wifi_proxy_24px),
        title = stringResource(R.string.settings_auto_proxy),
        description = stringResource(R.string.settings_auto_proxy_desc),
        checked = settingState.isUseProxy,
        booleanUserData = settingState.isUseProxyUserData
    )
}

fun selectDataFile(launcher: ManagedActivityResultLauncher<Intent, ActivityResult>) {
    val initUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initUri)
    }
    launcher.launch(Intent.createChooser(intent, "选择数据文件"))
}
