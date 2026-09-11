package indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryTopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back)) }
    }, actions = {
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.discovery_refresh)) }
        TextButton(onClick = onSettings) { Text(stringResource(R.string.nav_settings)) }
    })
}

@Composable
internal fun DiscoveryEmpty(message: String, onManageSources: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message)
        Button(onClick = onManageSources) { Text(stringResource(R.string.sources_title)) }
    }
}

@Composable
internal fun DiscoveryFailure(error: DiscoveryError, retry: () -> Unit, manage: () -> Unit, back: () -> Unit, field: String? = null) {
    val message = when (error) {
        DiscoveryError.Unsupported -> R.string.discovery_unsupported
        DiscoveryError.AuthenticationRequired -> R.string.discovery_login_required
        DiscoveryError.PermissionDenied -> R.string.discovery_permission
        DiscoveryError.InvalidRules -> R.string.discovery_invalid_rules
        DiscoveryError.Network -> R.string.discovery_network
        DiscoveryError.InvalidRequest -> R.string.discovery_invalid_target
        DiscoveryError.InvalidResponse -> R.string.discovery_invalid_response
        DiscoveryError.Unavailable -> R.string.discovery_unavailable
    }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
        field?.let { Text(stringResource(R.string.discovery_rule_field, it), style = MaterialTheme.typography.bodySmall) }
        Row {
            TextButton(onClick = retry) { Text(stringResource(R.string.discovery_retry)) }
            TextButton(onClick = manage) { Text(stringResource(R.string.sources_title)) }
            if (error == DiscoveryError.InvalidRequest || error == DiscoveryError.Unavailable)
                TextButton(onClick = back) { Text(stringResource(R.string.sources_back)) }
        }
    }
}

@Composable
internal fun DiscoveryFilterControl(filter: DiscoveryFilter, value: String, onChange: (String) -> Unit) {
    when (filter) {
        is DiscoveryFilter.Text -> {
            var draft by remember(value) { mutableStateOf(value) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { if (it.length <= 4096) draft = it },
                    label = { Text(filter.title) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(draft) }, enabled = draft != value) {
                    Text(stringResource(R.string.discovery_apply))
                }
            }
        }
        is DiscoveryFilter.Toggle -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(filter.title, Modifier.padding(vertical = 12.dp))
            Switch(checked = value == "true", onCheckedChange = { onChange(it.toString()) })
        }
        is DiscoveryFilter.Choice -> {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) { Text("${filter.title}: ${filter.options[value].orEmpty()}") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    filter.options.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                        open = false
                        onChange(key)
                    }) }
                }
            }
        }
        is DiscoveryFilter.Number -> {
            var draft by remember(value) { mutableStateOf(value) }
            val valid = draft.toIntOrNull()?.let { it in filter.min..filter.max } == true
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(filter.title) },
                    isError = !valid, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(draft) }, enabled = valid && draft != value) {
                    Text(stringResource(R.string.discovery_apply))
                }
            }
        }
    }
}
