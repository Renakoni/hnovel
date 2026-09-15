package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceCapability
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceListing
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySources
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibraryState
import indi.dmzz_yyhyy.lightnovelreader.ui.components.SectionHeader

@Composable
internal fun ZLibrarySettingsEditor(state: ZLibraryState, busy: Boolean, entry: SourceListing?, onEnabled: (Boolean) -> Unit,
    onSave: (String, String) -> Unit, onSearch: () -> Unit, network: SourceNetworkState?, onBypassVpn: (Boolean) -> Unit) {
    val settings = state.settings
    var origin by rememberSaveable(settings.origin) { mutableStateOf(settings.origin) }
    var permissions by rememberSaveable(settings.origins) { mutableStateOf(settings.origins.joinToString("\n")) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val enabledLabel = stringResource(R.string.sources_enabled)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(text = stringResource(R.string.sources_basic_section))
        ListItem(headlineContent = { Text(enabledLabel) },
            trailingContent = { Switch(settings.enabled, onEnabled, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = enabledLabel }) })
        Text(stringResource(R.string.zlibrary_capabilities))
        if (settings.available && SourceCapability.Search in entry.actionCapabilities()) Button(onClick = onSearch, enabled = !busy) { Text(stringResource(R.string.explore_search)) }
        network?.let {
            SectionHeader(text = stringResource(R.string.sources_network_section))
            SourceNetworkSection(it, busy, onBypassVpn)
        }
        SectionHeader(text = stringResource(R.string.sources_configuration))
        Text(settings.origin)
        TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.sources_advanced)) }
        if (advanced) {
            OutlinedTextField(origin, { origin = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.zlibrary_mirror)) })
            ZLibrarySources.MIRRORS.forEach { candidate ->
                TextButton(onClick = { origin = candidate }, enabled = !busy) { Text(candidate) }
            }
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
}
