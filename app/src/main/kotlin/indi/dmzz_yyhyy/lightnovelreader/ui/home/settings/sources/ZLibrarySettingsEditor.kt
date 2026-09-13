package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceCapability
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceListing
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceStatus
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySources
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibraryState

@Composable
internal fun ZLibrarySettingsEditor(state: ZLibraryState, busy: Boolean, entry: SourceListing?, onEnabled: (Boolean) -> Unit,
    onSave: (String, String) -> Unit, onInitialize: () -> Unit, onSearch: () -> Unit) {
    val settings = state.settings
    var origin by remember(settings.origin) { mutableStateOf(settings.origin) }
    var permissions by remember(settings.origins) { mutableStateOf(settings.origins.joinToString("\n")) }
    val enabledLabel = stringResource(R.string.sources_enabled)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListItem(headlineContent = { Text(enabledLabel) },
            supportingContent = { Text(stringResource(zLibraryStatus(state, entry))) },
            trailingContent = { Switch(settings.enabled, onEnabled, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = enabledLabel }) })
        Text(stringResource(R.string.zlibrary_capabilities))
        if (settings.available && SourceCapability.Search in entry.readyCapabilities()) Button(onClick = onSearch, enabled = !busy) { Text(stringResource(R.string.explore_search)) }
        if (entry?.status == SourceStatus.Registered) OutlinedButton(onClick = onInitialize, enabled = !busy) { Text(stringResource(R.string.sources_initialize)) }
        Text(stringResource(R.string.sources_no_discovery), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(origin, { origin = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
            label = { Text(stringResource(R.string.zlibrary_mirror)) })
        Text(stringResource(R.string.zlibrary_mirror_help), style = MaterialTheme.typography.bodySmall)
        ZLibrarySources.MIRRORS.forEach { candidate ->
            TextButton(onClick = { origin = candidate }, enabled = !busy) { Text(candidate) }
        }
        Text(stringResource(R.string.sources_permissions_help))
        Text(stringResource(R.string.sources_network_help), style = MaterialTheme.typography.bodySmall)
        if (state.denied.isNotEmpty()) {
            Text(stringResource(R.string.sources_denied_origins), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sources_origin_candidates_help))
            state.denied.forEach { denial ->
                SourcePermissionCandidate(denial.origin, denial.kind.name, permissions, busy) { permissions = it }
            }
        }
        OutlinedTextField(permissions, { permissions = it }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text(stringResource(R.string.sources_permissions)) })
        Button(onClick = { onSave(origin, permissions) }, enabled = !busy) { Text(stringResource(R.string.zlibrary_save)) }
    }
}

internal fun zLibraryStatus(state: ZLibraryState, entry: SourceListing?): Int = when {
    state.restorationFailed -> R.string.zlibrary_settings_failed
    !state.settings.enabled -> R.string.sources_disabled_by_user
    !state.settings.available -> R.string.sources_awaiting_permissions
    else -> sourceRuntimeStatus(entry)
}
