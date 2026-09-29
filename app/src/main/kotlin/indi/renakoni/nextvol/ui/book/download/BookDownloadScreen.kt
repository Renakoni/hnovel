package indi.renakoni.nextvol.ui.book.download

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.downloadFailureResource
import indi.renakoni.nextvol.ui.components.downloadStatusText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDownloadScreen(
    state: BookDownloadUiState, onBack: () -> Unit, onReload: () -> Unit,
    onSelect: (Set<String>) -> Unit, onRefresh: (Boolean) -> Unit,
    onSubmit: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit,
) {
    val chapters = remember(state.volumes) { state.allChapters }
    val indices = remember(chapters) { chapters.mapIndexed { index, chapter -> chapter.id to index + 1 }.toMap() }
    var rangeVisible by rememberSaveable { mutableStateOf(false) }
    val editable = state.ready && !state.locked
    if (rangeVisible) RangeDialog(chapters.map { it.id }, { rangeVisible = false }) {
        onSelect(it)
        rangeVisible = false
    }
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(stringResource(R.string.download_page_title)) },
            navigationIcon = { IconButton(onBack) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.download_back))
            } },
        ) },
        bottomBar = { Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (state.locked) R.string.download_selection_active else R.string.download_selection_count, state.selected.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onSubmit, Modifier.fillMaxWidth(), enabled = editable && state.selected.isNotEmpty(),
                    contentPadding = PaddingValues(16.dp)) {
                    Icon(painterResource(R.drawable.download_24px), null, Modifier.padding(end = 8.dp))
                    Text(stringResource(if (state.refresh) R.string.download_update_selected else R.string.download_start_selected, state.selected.size))
                }
            }
        } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "summary") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val information = state.information
                        Cover(state.bookId, 64.dp, 88.dp, information?.coverUri ?: Uri.EMPTY,
                            information?.title ?: stringResource(R.string.download_page_title))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(information?.title ?: stringResource(R.string.download_page_title),
                                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            information?.author?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(R.string.download_book_coverage, state.status.content.savedChapters,
                                if (state.ready) chapters.size else state.status.content.totalChapters),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (state.status.task.status != DownloadTaskStatus.None) item(key = "task") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(downloadStatusText(state.status), style = MaterialTheme.typography.bodyMedium)
                        if (state.status.task.active) {
                            if (state.status.task.status == DownloadTaskStatus.Queued || state.status.task.stage in
                                setOf(DownloadStage.Unknown, DownloadStage.Details, DownloadStage.Directory)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else LinearProgressIndicator(
                                progress = { (state.status.content.taskSavedChapters.toFloat() /
                                    state.status.content.taskTotalChapters.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row {
                            if (state.status.task.canResume && (!state.locked || state.status.task.status == DownloadTaskStatus.WaitingVerification))
                                TextButton(onResume, enabled = !state.submitting) {
                                Text(stringResource(if (state.status.task.status == DownloadTaskStatus.WaitingVerification)
                                    R.string.download_task_verify else R.string.download_resume_selection))
                            }
                            if (state.locked) TextButton(onCancel, enabled = !state.submitting) {
                                Text(stringResource(R.string.download_cancel_reselect))
                            }
                        }
                    }
                }
            }
            when {
                state.loading -> item(key = "directory-loading") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.download_directory_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.directoryFailure != null -> item(key = "directory-error") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.download_directory_failed), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(downloadFailureResource(state.directoryFailure)), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onReload) { Text(stringResource(R.string.download_directory_retry)) }
                    }
                }
                else -> {
                    item(key = "selection-tools") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.download_directory_ready, chapters.size), style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton({ onSelect(chapters.map { it.id }.toSet()) }, enabled = editable) { Text(stringResource(R.string.download_select_all)) }
                                TextButton({ onSelect(chapters.filter { state.chapters.chapters[it.id]?.current != true }.map { it.id }.toSet()) }, enabled = editable) {
                                    Text(stringResource(R.string.download_select_missing))
                                }
                                TextButton({ rangeVisible = true }, enabled = editable) { Text(stringResource(R.string.download_select_range)) }
                                TextButton({ onSelect(emptySet()) }, enabled = editable) { Text(stringResource(R.string.download_select_none)) }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.download_refresh_selected_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Switch(state.refresh, onRefresh, enabled = editable)
                            }
                        }
                    }
                    state.volumes?.volumes?.forEachIndexed { volumeIndex, volume ->
                        item(key = "volume:$volumeIndex") {
                            val ids = volume.chapters.map { it.id }.toSet()
                            val selected = ids.count { it in state.selected }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TriStateCheckbox(when (selected) { 0 -> ToggleableState.Off; ids.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                                    onClick = { onSelect(if (selected == ids.size) state.selected - ids else state.selected + ids) }, enabled = editable,
                                    modifier = Modifier.testTag("download-volume-$volumeIndex"))
                                Column(Modifier.weight(1f)) {
                                    Text(volume.volumeTitle.ifBlank { stringResource(R.string.download_volume_number, volumeIndex + 1) }, style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.download_volume_selection, selected, ids.size), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        items(volume.chapters, key = { "chapter:${it.id}" }) { chapter ->
                            val saved = state.chapters.chapters[chapter.id]
                            val active = state.status.task.active && state.status.task.chapterId == chapter.id
                            Row(Modifier.fillMaxWidth().toggleable(chapter.id in state.selected, enabled = editable, role = Role.Checkbox) {
                                onSelect(if (it) state.selected + chapter.id else state.selected - chapter.id)
                            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(chapter.id in state.selected, onCheckedChange = null, enabled = editable, modifier = Modifier.padding(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${indices[chapter.id]}. ${chapter.title}", style = MaterialTheme.typography.bodyMedium)
                                    val label = when {
                                        active -> R.string.download_chapter_downloading
                                        saved?.failure != null -> downloadFailureResource(saved.failure)
                                        saved?.current == true -> R.string.download_chapter_saved
                                        saved?.downloaded == true -> R.string.download_chapter_outdated
                                        else -> R.string.download_chapter_missing
                                    }
                                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = when {
                                        saved?.failure != null -> MaterialTheme.colorScheme.error
                                        active || saved?.current == true -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    })
                                }
                                if (saved?.current == true) Icon(painterResource(R.drawable.check_24px), null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeDialog(ids: List<String>, onDismiss: () -> Unit, onSelect: (Set<String>) -> Unit) {
    var first by rememberSaveable { mutableStateOf("1") }
    var last by rememberSaveable { mutableStateOf(ids.size.toString()) }
    val selected = downloadRange(ids, first.toIntOrNull(), last.toIntOrNull())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.download_select_range)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.download_range_hint, ids.size))
            OutlinedTextField(first, { first = it }, label = { Text(stringResource(R.string.download_range_first)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = selected == null)
            OutlinedTextField(last, { last = it }, label = { Text(stringResource(R.string.download_range_last)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = selected == null)
        } },
        confirmButton = { TextButton({ selected?.let(onSelect) }, enabled = selected != null) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
