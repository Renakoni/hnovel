package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import indi.renakoni.nextvol.R

@Composable
internal fun SourceNetworkSection(state: SourceNetworkState, busy: Boolean, onBypassVpn: (Boolean) -> Unit) {
    val label = stringResource(R.string.sources_bypass_vpn)
    ListItem(headlineContent = { Text(label) },
        supportingContent = { Text(stringResource(state.limitation ?: R.string.sources_bypass_vpn_help)) },
        trailingContent = {
            Switch(state.bypassVpn, onBypassVpn, enabled = !busy && (state.limitation == null || state.bypassVpn),
                modifier = Modifier.semantics { contentDescription = label })
        })
}
