package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpSpeechSourcesScreen(
    state: HttpSpeechSourcesState, onBack: () -> Unit, onImport: () -> Unit,
    onSelect: (String?) -> Unit, onDelete: (SavedHttpSpeechSource) -> Unit,
    onSites: (SavedHttpSpeechSource, String) -> Unit, onConfirmImport: () -> Unit, onDismissImport: () -> Unit,
    onEdit: (SavedHttpSpeechSource, String, String, String) -> Unit,
) {
    var editing by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var sites by remember { mutableStateOf("") }
    var definition by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.tts_online_sources)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back)) }
        }, actions = {
            if (state.busy) CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp), strokeWidth = 2.dp)
            else TextButton(onClick = onImport) { Text(stringResource(R.string.tts_import_sources)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup(), contentPadding = PaddingValues(16.dp)) {
            state.error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            item {
                SourceChoice(stringResource(R.string.tts_system_speech), state.selected == null, !state.busy, { onSelect(null) })
            }
            if (state.sources.isEmpty() && !state.busy) item { Text(stringResource(R.string.tts_online_empty), Modifier.padding(16.dp)) }
            items(state.sources, key = { it.definition.id }) { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceChoice(source.definition.name, state.selected == source.definition.id, !state.busy,
                        { onSelect(source.definition.id) }, Modifier.weight(1f))
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !state.busy) {
                            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.tts_edit_source)) }, onClick = {
                                menu = false; definition = source; name = source.definition.name
                                url = source.definition.url; header = source.definition.text("header")
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.tts_source_sites)) }, onClick = {
                                menu = false; editing = source; sites = source.origins.joinToString("\n")
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.tts_remove_source)) }, onClick = { menu = false; onDelete(source) })
                        }
                    }
                }
            }
        }
    }
    state.pending?.let { pending ->
        AlertDialog(onDismissRequest = onDismissImport, title = { Text(pluralStringResource(R.plurals.tts_import_count, pending.size, pending.size)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item { Text(stringResource(R.string.tts_online_privacy), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.invalid > 0) item { Text(pluralStringResource(R.plurals.tts_import_invalid, state.invalid, state.invalid), color = MaterialTheme.colorScheme.error) }
                    items(pending, key = { it.definition.id }) { source ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(source.definition.name, style = MaterialTheme.typography.bodyLarge)
                            if (source.origins.isNotEmpty()) Text(source.origins.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (source.definition.usesMicrosoftTranslator) Text(stringResource(R.string.tts_microsoft_sites),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = onConfirmImport, enabled = pending.isNotEmpty() && !state.busy) {
                Text(stringResource(R.string.tts_import_sources))
            } }, dismissButton = { TextButton(onClick = onDismissImport, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } })
    }
    editing?.let { source ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.tts_source_sites)) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(sites, { sites = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
                state.deniedOrigins[source.definition.id].orEmpty().forEach { origin ->
                    if (origin !in sites.lineSequence().map(String::trim).toSet()) TextButton(onClick = {
                        sites = (sites.trim() + "\n" + origin).trim()
                    }) { Text(stringResource(R.string.tts_allow_source_site, origin)) }
                }
                if (source.definition.usesMicrosoftTranslator) Text(stringResource(R.string.tts_microsoft_sites),
                    style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { onSites(source, sites); editing = null }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    definition?.let { source ->
        AlertDialog(onDismissRequest = { definition = null }, title = { Text(stringResource(R.string.tts_edit_source)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.tts_source_name)) }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.tts_source_request)) }, minLines = 2, maxLines = 5)
                    OutlinedTextField(header, { header = it }, label = { Text(stringResource(R.string.tts_source_headers)) }, minLines = 2, maxLines = 5)
                }
            }, confirmButton = { TextButton(onClick = { onEdit(source, name, url, header); definition = null }, enabled = name.isNotBlank() && url.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            } }, dismissButton = { TextButton(onClick = { definition = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SourceChoice(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
