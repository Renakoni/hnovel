package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hnovel.imports.*
import indi.dmzz_yyhyy.lightnovelreader.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Selection and permissions are drafts; only the fixed confirmation action commits them. */
@Composable
internal fun SourceImportPreview(state: SourceManagementState, model: SourcesViewModel, modifier: Modifier = Modifier) {
    val preview = checkNotNull(state.preview)
    var selected by rememberSaveable(preview) { mutableStateOf(emptyList<Int>()) }
    var filter by rememberSaveable(preview) { mutableIntStateOf(0) }
    var approveIdentity by rememberSaveable(preview) { mutableStateOf(false) }
    var permissions by rememberSaveable(preview, stateSaver = mapSaver<Map<Int, String>>(
        save = { values -> values.mapKeys { it.key.toString() } },
        restore = { values -> values.map { it.key.toInt() to it.value as String }.toMap() })) {
        mutableStateOf(preview.candidates.associate { it.index to runCatching { SourcesViewModel.origin(it.importKey) }.getOrDefault("") })
    }
    val visible = preview.candidates.filter { when (filter) { 1 -> it.existing == null; 2 -> it.existing != null; else -> true } }
    fun select(candidate: SourceCandidate, checked: Boolean) {
        selected = if (!checked) selected - candidate.index
        else if (state.updateTarget != null) listOf(candidate.index)
        else (selected - candidate.duplicateIndexes.toSet() - candidate.index) + candidate.index
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sources_preview), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.sources_permissions_help), style = MaterialTheme.typography.bodySmall)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    if (state.updateTarget == null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(R.string.sources_filter_all, R.string.sources_filter_new, R.string.sources_filter_updates).forEachIndexed { index, title ->
                                FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(stringResource(title)) })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                selected = (selected + visible.filter { it.duplicateIndexes.isEmpty() }.map { it.index }).distinct()
                            }, enabled = !state.busy && visible.any { it.duplicateIndexes.isEmpty() }) { Text(stringResource(R.string.sources_select_visible)) }
                            TextButton(onClick = { selected = emptyList() }, enabled = !state.busy && selected.isNotEmpty()) {
                                Text(stringResource(R.string.sources_clear_selection))
                            }
                        }
                    }
                }
            }
            items(preview.issues) { issue ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(importProblem(issue.code)), style = MaterialTheme.typography.bodyMedium)
                        issue.index?.let { Text(stringResource(R.string.sources_import_entry, it + 1), style = MaterialTheme.typography.labelMedium) }
                        ImportField(issue.field)
                    }
                }
            }
            if (visible.isEmpty()) item { Text(stringResource(R.string.sources_filter_empty)) }
            items(visible, key = { it.index }) { candidate ->
                var showNotes by rememberSaveable(preview, candidate.index) { mutableStateOf(false) }
                val checked = candidate.index in selected
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = !state.busy, role = Role.Checkbox,
                            onValueChange = { select(candidate, it) }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked, null, enabled = !state.busy)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(candidate.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(if (candidate.existing == null) R.string.sources_new else R.string.sources_replace),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!candidate.enabled) Text(stringResource(R.string.sources_disabled_definition), style = MaterialTheme.typography.bodySmall)
                        if (candidate.duplicateIndexes.isNotEmpty()) Text(stringResource(R.string.sources_duplicate_choice),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (candidate.notices.isNotEmpty()) {
                            TextButton(onClick = { showNotes = !showNotes }) { Text(stringResource(R.string.sources_import_notes, candidate.notices.size)) }
                            if (showNotes) candidate.notices.forEach { notice ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(importNotice(notice.code)), style = MaterialTheme.typography.bodySmall,
                                        color = if (notice.code == "UnclassifiedField") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    ImportField(notice.field)
                                }
                            }
                        }
                        if (checked) {
                            val origins = remember(candidate) { SourceOriginCandidates.discover(Json.parseToJsonElement(candidate.rawJson).jsonObject) }
                            Text(stringResource(R.string.sources_origin_candidates_help), style = MaterialTheme.typography.bodySmall)
                            origins.forEach { origin ->
                                SourcePermissionCandidate(origin.origin, origin.kind.name, permissions[candidate.index].orEmpty(), state.busy) {
                                    permissions = permissions + (candidate.index to it)
                                }
                            }
                            OutlinedTextField(permissions[candidate.index].orEmpty(), { permissions = permissions + (candidate.index to it) },
                                Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_permissions)) }, enabled = !state.busy)
                        }
                    }
                }
            }
            if (state.updateTarget != null) item {
                Row(Modifier.fillMaxWidth().toggleable(approveIdentity, enabled = !state.busy, role = Role.Checkbox,
                    onValueChange = { approveIdentity = it })) {
                    Checkbox(approveIdentity, null, enabled = !state.busy)
                    Text(stringResource(R.string.sources_identity_approval), Modifier.weight(1f))
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.sources_selected_count, selected.size, preview.candidates.size), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { if (state.busy) model.cancel() else model.dismissPreview() }) { Text(stringResource(android.R.string.cancel)) }
                }
                Button(onClick = { model.commit(selected.toSet(), permissions.filterKeys { it in selected }, approveIdentity) },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy && selected.isNotEmpty()) { Text(stringResource(R.string.sources_apply)) }
            }
        }
    }
}

@Composable
private fun ImportField(field: String?) {
    // A parser field can originate in untrusted object keys. Never echo arbitrary values or messages.
    field?.takeIf { it.matches(Regex("[A-Za-z0-9_.\\[\\]-]{1,120}")) }?.let {
        Text(stringResource(R.string.sources_notice_field, it), style = MaterialTheme.typography.labelSmall)
    }
}

private fun importNotice(code: String): Int = when (code) {
    "KnownPackageAdaptation" -> R.string.sources_notice_known_package
    "ExecutionCompatibilityPending" -> R.string.sources_notice_execution
    "UnclassifiedField" -> R.string.sources_notice_unknown
    "ExternalOrderRetainedNotApplied" -> R.string.sources_notice_order
    "DiscoveryExtension" -> R.string.sources_notice_extension
    else -> R.string.sources_notice_other
}

private fun importProblem(code: ImportCode): Int = when (code) {
    ImportCode.TooDeep, ImportCode.TooLarge, ImportCode.TooMany -> R.string.sources_import_limit
    ImportCode.UnsupportedFormat, ImportCode.UnsupportedProfile, ImportCode.UnsupportedType -> R.string.sources_import_unsupported
    ImportCode.PluginPackage -> R.string.sources_import_package
    ImportCode.ReadFailed -> R.string.sources_import_read_failed
    ImportCode.DownloadFailed -> R.string.sources_import_download_failed
    ImportCode.StorageUnavailable, ImportCode.StorageQuota -> R.string.sources_import_storage_failed
    ImportCode.Conflict, ImportCode.StalePreview -> R.string.sources_import_stale
    ImportCode.DuplicateSelection, ImportCode.InvalidSelection -> R.string.sources_import_selection_failed
    else -> R.string.sources_import_invalid_format
}
