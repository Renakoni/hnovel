package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.rules.InstalledRuleSource
import indi.renakoni.nextvol.data.web.rules.SourceGroup

@Composable
internal fun SourceManagementAction(title: String, icon: Int, enabled: Boolean, modifier: Modifier,
    onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 100.dp),
        shape = MaterialTheme.shapes.extraLarge, contentPadding = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon), null)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
internal fun SourceGroupFilters(groups: List<SourceGroup>, installed: List<InstalledRuleSource>,
    selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") { FilterChip(selected == null, { onSelect(null) },
            label = { Text(stringResource(R.string.sources_filter_all)) }) }
        item(key = "ungrouped") { FilterChip(selected == "", { onSelect("") },
            label = { Text(stringResource(R.string.source_group_ungrouped)) },
            trailingIcon = { Text(installed.count { it.preferences.groupId == null }.toString()) }) }
        items(groups, key = { it.id }) { group ->
            FilterChip(selected == group.id, { onSelect(group.id) },
                label = { Text(group.name, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Text(installed.count { it.preferences.groupId == group.id }.toString()) })
        }
    }
}

@Composable
internal fun SourceGroupsDialog(groups: List<SourceGroup>, installed: List<InstalledRuleSource>, busy: Boolean,
    choosing: Boolean, message: Int?, revision: Long, onDismiss: () -> Unit, onChoose: (String?) -> Unit, onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit, onDelete: (String) -> Unit) {
    var creating by rememberSaveable(revision) { mutableStateOf(false) }
    var editing by rememberSaveable(revision) { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable(revision) { mutableStateOf<String?>(null) }
    val edited = groups.find { it.id == editing }
    val deleted = groups.find { it.id == deleting }
    val naming = creating || edited != null
    var name by rememberSaveable(creating, editing) { mutableStateOf(edited?.name.orEmpty()) }
    val normalized = name.trim()
    val valid = SourceGroup.validName(normalized) && groups.none { it.id != edited?.id && it.name.equals(normalized, true) }
    fun dismiss() {
        when {
            naming -> { creating = false; editing = null }
            deleted != null -> deleting = null
            else -> onDismiss()
        }
    }
    AlertDialog(onDismissRequest = ::dismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(when {
            naming -> stringResource(if (edited == null) R.string.source_group_create else R.string.source_group_rename)
            deleted != null -> stringResource(R.string.source_group_delete_title, deleted.name)
            else -> stringResource(if (choosing) R.string.source_group_move else R.string.source_groups_manage)
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message == R.string.sources_action_failed) Text(stringResource(message), color = MaterialTheme.colorScheme.error)
            when {
                naming -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                        label = { Text(stringResource(R.string.source_group_name)) }, isError = name.isNotEmpty() && !valid)
                    Text(stringResource(R.string.source_group_name_help), style = MaterialTheme.typography.bodySmall)
                }
                deleted != null -> Text(stringResource(R.string.source_group_delete_help))
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.source_groups_help), style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        if (choosing) item {
                            ListItem(headlineContent = { Text(stringResource(R.string.source_group_ungrouped)) },
                                modifier = Modifier.clickable(enabled = !busy) { onChoose(null) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                        }
                        items(groups, key = { it.id }) { group ->
                            ListItem(headlineContent = { Text(group.name) },
                                supportingContent = { Text(stringResource(R.string.source_catalog_installed,
                                    installed.count { it.preferences.groupId == group.id })) },
                                modifier = if (choosing) Modifier.clickable(enabled = !busy) { onChoose(group.id) } else Modifier,
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                trailingContent = if (choosing) null else ({
                                    Row {
                                        IconButton(onClick = { editing = group.id }, enabled = !busy) {
                                            Icon(painterResource(R.drawable.edit_square_24px), stringResource(R.string.source_group_rename_named, group.name))
                                        }
                                        IconButton(onClick = { deleting = group.id }, enabled = !busy) {
                                            Icon(painterResource(R.drawable.delete_forever_24px), stringResource(R.string.source_group_delete_title, group.name))
                                        }
                                    }
                                }))
                        }
                        if (groups.isEmpty() && !choosing) item {
                            Text(stringResource(R.string.source_groups_empty), Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(onClick = { creating = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.add_circle_24px), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.source_group_create))
                    }
                }
            }
            }
        }, confirmButton = {
            when {
                naming -> TextButton(enabled = valid && !busy, onClick = {
                    if (edited == null) onCreate(normalized) else onRename(edited.id, normalized)
                }) { Text(stringResource(R.string.source_group_save)) }
                deleted != null -> TextButton(enabled = !busy, onClick = { onDelete(deleted.id) }) {
                    Text(stringResource(R.string.source_group_delete), color = MaterialTheme.colorScheme.error)
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        }, dismissButton = {
            if (naming || deleted != null) TextButton(onClick = ::dismiss) { Text(stringResource(android.R.string.cancel)) }
        })
}
