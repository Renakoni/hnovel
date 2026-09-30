package indi.renakoni.nextvol.ui.book.detail

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.rules.PixivBlockKind
import indi.renakoni.nextvol.utils.textToast
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.launch

@Composable
internal fun PixivBlockingFeedback(model: PixivBlockingViewModel) {
    val context = LocalContext.current
    val message = model.state.message
    LaunchedEffect(message) {
        message?.let {
            model.consumeMessage()
            textToast(context, it, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun PixivBlockMenu(model: PixivBlockingViewModel, dismiss: () -> Unit) {
    val state = model.state
    val book = state.book ?: return
    DropdownMenuItem(text = { Text(stringResource(R.string.pixiv_block_book)) },
        enabled = !state.busy && !state.contains(PixivBlockKind.Book, book.key),
        onClick = { dismiss(); model.request(PixivBlockKind.Book) })
    if (book.authorId.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.pixiv_block_author)) },
        enabled = !state.busy && !state.contains(PixivBlockKind.Author, book.authorId),
        onClick = { dismiss(); model.request(PixivBlockKind.Author) })
    if (book.tags.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.pixiv_block_tags)) },
        enabled = !state.busy, onClick = { dismiss(); model.request(PixivBlockKind.Tag) })
}

@Composable
internal fun PixivBlockConfirmation(model: PixivBlockingViewModel) {
    val state = model.state
    val kind = state.confirmation ?: return
    val book = state.book ?: return
    var selected by remember(book.key, kind) { mutableStateOf(emptySet<String>()) }
    val isTags = kind == PixivBlockKind.Tag
    val title = when (kind) {
        PixivBlockKind.Book -> R.string.pixiv_block_book
        PixivBlockKind.Author -> R.string.pixiv_block_author
        else -> R.string.pixiv_block_tags
    }
    AlertDialog(onDismissRequest = model::cancel, title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(stringResource(if (isTags) R.string.pixiv_block_tag_hint else R.string.pixiv_block_scope))
                Spacer(Modifier.height(12.dp))
                if (isTags) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(book.tags, key = { it }) { tag ->
                        val blocked = state.contains(PixivBlockKind.Tag, tag)
                        Row(Modifier.fillMaxWidth().toggleable(value = blocked || tag in selected,
                            enabled = !state.busy && !blocked, role = Role.Checkbox, onValueChange = { checked ->
                                selected = if (checked) selected + tag else selected - tag
                            }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = blocked || tag in selected, onCheckedChange = null, enabled = !state.busy && !blocked)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(tag, style = MaterialTheme.typography.bodyLarge)
                                if (blocked) Text(stringResource(R.string.pixiv_block_already),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else Text(if (kind == PixivBlockKind.Book) book.title else book.author.ifBlank { book.authorId },
                    style = MaterialTheme.typography.titleMedium)
            }
        },
        confirmButton = { TextButton(enabled = !state.busy && (!isTags || selected.isNotEmpty()),
            onClick = { model.confirm(selected) }) { Text(stringResource(R.string.pixiv_block_confirm)) } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = model::cancel) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PixivBlockManager(id: Identifier, model: PixivBlockingViewModel) {
    val state = model.state
    val scope = rememberCoroutineScope()
    PixivBlockingFeedback(model)
    val kinds = listOf(PixivBlockKind.Author, PixivBlockKind.Tag, PixivBlockKind.Book) +
        listOfNotNull(PixivBlockKind.Caption.takeIf { state.rules.any { it.kind == PixivBlockKind.Caption } })
    var selected by remember(id) { mutableStateOf(PixivBlockKind.Author) }
    val kind = selected.takeIf { it in kinds } ?: PixivBlockKind.Author
    val rows = state.rules.filter { it.kind == kind }
    val counts = state.rules.groupingBy { it.kind }.eachCount()
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = kinds.indexOf(kind), containerColor = MaterialTheme.colorScheme.surface) {
            kinds.forEach { tab ->
                Tab(selected = kind == tab, onClick = { selected = tab },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(when (tab) {
                                PixivBlockKind.Author -> R.string.pixiv_block_authors
                                PixivBlockKind.Tag -> R.string.pixiv_block_tag_rules
                                PixivBlockKind.Book -> R.string.pixiv_block_books
                                PixivBlockKind.Caption -> R.string.pixiv_block_legacy_captions
                            }), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (!state.busy || state.rules.isNotEmpty()) Text((counts[tab] ?: 0).toString(),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
            }
        }
        key(kind) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { Text(stringResource(R.string.pixiv_block_scope), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.busy && state.rules.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (state.failed) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sources_action_failed))
                        TextButton(onClick = { scope.launch { model.loadManager(id) } }) { Text(stringResource(R.string.action_retry)) }
                    }
                } else if (rows.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().fillParentMaxHeight(0.52f).padding(horizontal = 32.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.pixiv_block_empty), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.pixiv_block_empty_hint), Modifier.padding(top = 8.dp).widthIn(max = 280.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                }
                itemsIndexed(rows, key = { _, rule -> rule.kind.name + ":" + rule.value }) { index, rule ->
                    ListItem(headlineContent = { Text(rule.label, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (kind == PixivBlockKind.Author && rule.value != rule.label)
                            { { Text(rule.value) } } else null,
                        trailingContent = { TextButton(enabled = !state.busy, onClick = { model.remove(rule) }) {
                            Text(stringResource(R.string.pixiv_block_remove))
                        } }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.padding(horizontal = 8.dp))
                    if (index < rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
