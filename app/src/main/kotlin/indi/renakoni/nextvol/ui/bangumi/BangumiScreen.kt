package indi.renakoni.nextvol.ui.bangumi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bangumi.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BangumiScreen(
    state: BangumiUiState, bookId: String?, onBack: () -> Unit, onAccount: () -> Unit, onBook: (String) -> Unit,
    onConnect: (String) -> Unit, onDisconnect: () -> Unit, onQuery: (String) -> Unit,
    onSearch: () -> Unit, onMore: () -> Unit, onChoose: (Int) -> Unit,
    onUnlink: (String) -> Unit, onRetryFailures: () -> Unit,
    onMapping: (String, String?) -> Unit, onComplete: (String, Boolean) -> Unit, onBaseline: (String, Boolean) -> Unit,
    onPrivate: (Boolean) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }
    var unlink by remember { mutableStateOf<String?>(null) }
    var selectingBook by remember { mutableStateOf(false) }
    var showRecords by remember { mutableStateOf(false) }
    var changeToken by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val listState = rememberLazyListState()
    LaunchedEffect(bookId, state.preview?.subject?.id, selectingBook, showRecords) { listState.scrollToItem(0) }
    LaunchedEffect(state.account.user?.id) {
        token = ""; changeToken = false; accountMenu = false
        selectingBook = false; showRecords = false
    }
    LaunchedEffect(state.busy, state.error) { if (!state.busy && state.error == null) changeToken = false }
    BackHandler(state.preview != null && !state.busy, onDismiss)
    BackHandler(selectingBook || showRecords) { selectingBook = false; showRecords = false }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(when {
        selectingBook -> R.string.bangumi_choose_book
        showRecords -> R.string.bangumi_records
        else -> R.string.bangumi_title
    })) }, navigationIcon = {
        IconButton(onClick = {
            when {
                selectingBook || showRecords -> { selectingBook = false; showRecords = false }
                state.preview != null -> onDismiss()
                else -> onBack()
            }
        }) {
            Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
        }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy || !state.account.loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
            if (selectingBook) {
                val available = state.localBooks.filter { book -> state.bindings.none { it.bookId == book.id } }
                if (available.isEmpty()) item { Text(stringResource(R.string.bangumi_no_local_books)) }
                items(available, key = { it.id }) { book ->
                    OutlinedCard(onClick = { selectingBook = false; onBook(book.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(book.title, Modifier.padding(16.dp))
                    }
                }
            } else if (bookId == null && showRecords) {
                item { Text(stringResource(R.string.bangumi_records_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.records.isEmpty()) item { Text(stringResource(R.string.bangumi_no_records)) }
                items(state.records, key = { it.id }) { record ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(record.bookTitle, style = MaterialTheme.typography.titleMedium)
                            if (record.httpStatus != null) Text(stringResource(R.string.bangumi_http_failure, record.httpStatus,
                                stringResource(bangumiHttpError(record.httpStatus))), color = MaterialTheme.colorScheme.error)
                            else Text(stringResource(when (record.status) {
                                BangumiSyncStatus.OFFLINE -> R.string.bangumi_no_response
                                BangumiSyncStatus.REQUEST_REJECTED -> R.string.bangumi_invalid_response
                                else -> statusText(record.status)
                            }))
                            if (record.pendingConfirmation) Text(stringResource(R.string.bangumi_pending_confirmation), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.bangumi_progress, record.target, record.remote))
                            Text(DateFormat.getDateTimeInstance().format(Date(record.timestamp)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (bookId == null) {
                item {
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val user = state.account.user
                            if (user != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Icon(painterResource(R.drawable.public_24px), null, Modifier.padding(12.dp).size(24.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(user.nickname.ifBlank { user.username }, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.bangumi_connected), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box {
                                    IconButton(onClick = { accountMenu = true }, enabled = !state.busy) {
                                        Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.bangumi_account_actions))
                                    }
                                    DropdownMenu(accountMenu, { accountMenu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.bangumi_change_token)) }, onClick = {
                                            accountMenu = false; changeToken = true
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.bangumi_disconnect)) }, onClick = {
                                            accountMenu = false; disconnect = true
                                        })
                                    }
                                }
                            } else {
                                Text(stringResource(R.string.bangumi_connect), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.bangumi_description), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.account.unreadable) Text(stringResource(R.string.bangumi_error_storage), color = MaterialTheme.colorScheme.error)
                            if (user == null || changeToken) {
                                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(stringResource(R.string.bangumi_token)) },
                                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy && state.account.loaded)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { val value = token; token = ""; onConnect(value) }, enabled = token.isNotBlank() && !state.busy && state.account.loaded) {
                                        Text(stringResource(R.string.bangumi_connect))
                                    }
                                    if (user != null) TextButton(onClick = { changeToken = false; token = "" }, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
                                }
                                TextButton(onClick = { uri.openUri("https://next.bgm.tv/demo/access-token") }) { Text(stringResource(R.string.bangumi_open_token)) }
                            }
                        }
                    }
                }
                if (state.account.user != null) {
                    item {
                        val statuses = state.bindings.map { it.binding().status }
                        val pending = statuses.count { it in setOf(BangumiSyncStatus.PENDING, BangumiSyncStatus.OFFLINE) }
                        val errors = statuses.count { it !in setOf(BangumiSyncStatus.READY, BangumiSyncStatus.PENDING,
                            BangumiSyncStatus.SYNCED, BangumiSyncStatus.REMOTE_AHEAD, BangumiSyncStatus.OFFLINE) }
                        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.bangumi_sync_state), style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = { showRecords = true }) { Text(stringResource(R.string.bangumi_records)) }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    SyncCount(pending, stringResource(R.string.bangumi_pending_count), Modifier.weight(1f))
                                    VerticalDivider(Modifier.height(40.dp))
                                    SyncCount(errors, stringResource(R.string.bangumi_attention_count), Modifier.weight(1f), errors > 0)
                                }
                                if (statuses.any { it in setOf(BangumiSyncStatus.OFFLINE, BangumiSyncStatus.REQUEST_REJECTED) })
                                    TextButton(onClick = onRetryFailures, enabled = !state.busy) { Text(stringResource(R.string.bangumi_retry_failures)) }
                            }
                        }
                    }
                    if (state.bindings.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(painterResource(R.drawable.menu_book_24px), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.bangumi_no_bindings), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { selectingBook = true }, enabled = !state.busy) { Text(stringResource(R.string.bangumi_add_binding)) }
                        }
                    } else item {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.bangumi_linked_books), style = MaterialTheme.typography.titleMedium)
                            FilledTonalButton(onClick = { selectingBook = true }, enabled = !state.busy) { Text(stringResource(R.string.bangumi_add_binding)) }
                        }
                    }
                }
                items(state.bindings, key = { it.bookId }) { entity ->
                    BindingCard(entity, state.busy, onOpen = { onBook(entity.bookId) }, onUnlink = { unlink = entity.bookId })
                }
            } else if (state.preview != null) {
                val preview = state.preview
                val count = state.mapping.mapNotNull { it.editionKey }.distinct().size
                item {
                    Text(preview.subject.title, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.bangumi_mapping_summary, count, preview.subject.volumes))
                    if (!preview.subject.series) Text(stringResource(R.string.bangumi_single_help))
                    Text(stringResource(R.string.bangumi_mapping_help), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.bangumi_baseline_help, preview.remote?.volumes ?: 0))
                    Text(stringResource(R.string.bangumi_baseline_count, state.baseline.size), style = MaterialTheme.typography.bodySmall)
                    if (preview.remote != null && preview.remote.type != 3) Text(stringResource(R.string.bangumi_resume_warning))
                }
                items(state.mapping, key = { it.volumeId }) { row ->
                    MappingCard(row, state.mapping, preview.mapping.first { it.volumeId == row.volumeId }.editionKey,
                        row.editionKey in state.baseline, (preview.remote?.volumes ?: 0) > 0,
                        !state.busy, onMapping, onComplete, onBaseline)
                }
                if (preview.remote == null) item { Choice(stringResource(R.string.bangumi_private), state.privateCollection, !state.busy, onPrivate) }
                item {
                    Button(onClick = onConfirm, enabled = !state.busy && count > 0 && (preview.subject.series || count == 1) &&
                        state.baseline.size <= (preview.remote?.volumes ?: 0), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bangumi_confirm))
                    }
                    TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
                }
            } else {
                state.book?.let { book -> item { Text(book.title, style = MaterialTheme.typography.titleLarge) } }
                state.bindings.find { it.bookId == bookId }?.let { entity -> item {
                    Text(entity.binding().subjectTitle)
                    TextButton(onClick = { onChoose(entity.subjectId) }, enabled = !state.busy) { Text(stringResource(R.string.bangumi_review_mapping)) }
                } }
                if (state.account.user == null) item {
                    Text(stringResource(R.string.bangumi_connect_before_binding))
                    Button(onClick = onAccount) { Text(stringResource(R.string.bangumi_connect)) }
                }
                item {
                    OutlinedTextField(state.query, onQuery, label = { Text(stringResource(R.string.bangumi_search_hint)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                    TextButton(onClick = onSearch, enabled = state.query.isNotBlank() && !state.busy) { Text(stringResource(R.string.bangumi_search)) }
                }
                if (state.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.searched && state.candidates.isEmpty() && !state.searching) item { Text(stringResource(R.string.bangumi_no_results)) }
                items(state.candidates, key = { it.subject.id }) { candidate ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(candidate.subject.title, style = MaterialTheme.typography.titleMedium)
                            if (candidate.subject.name != candidate.subject.title) Text(candidate.subject.name, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(if (candidate.subject.series && candidate.subject.isNovel) R.string.bangumi_series else R.string.bangumi_single,
                                candidate.subject.volumes))
                            Text(candidate.subject.values("作者").joinToString(" / "))
                            if (candidate.titleMatches) Text(stringResource(R.string.bangumi_title_matches), style = MaterialTheme.typography.bodySmall)
                            if (candidate.authorMatches) Text(stringResource(R.string.bangumi_author_matches), style = MaterialTheme.typography.bodySmall)
                            if (candidate.publisherMatches) Text(stringResource(R.string.bangumi_publisher_matches), style = MaterialTheme.typography.bodySmall)
                            if (candidate.authorConflicts) Text(stringResource(R.string.bangumi_author_check), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { onChoose(candidate.subject.id) }, enabled = state.account.user != null && !state.busy) {
                                Text(stringResource(R.string.bangumi_review_mapping))
                            }
                        }
                    }
                }
                if (state.hasMore) item { TextButton(onClick = onMore, enabled = !state.searching && !state.busy) { Text(stringResource(R.string.bangumi_more)) } }
            }
        }
    }
    if (disconnect || unlink != null) AlertDialog(onDismissRequest = { disconnect = false; unlink = null },
        title = { Text(stringResource(if (disconnect) R.string.bangumi_disconnect else R.string.bangumi_unlink)) },
        text = { Text(stringResource(if (disconnect) R.string.bangumi_disconnect_help else R.string.bangumi_unlink_help)) },
        confirmButton = { TextButton(onClick = {
            if (disconnect) onDisconnect() else unlink?.let(onUnlink)
            disconnect = false; unlink = null
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { disconnect = false; unlink = null }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SyncCount(count: Int, label: String, modifier: Modifier, attention: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label $count" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(count.toString(), style = MaterialTheme.typography.displayMedium,
            color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BindingCard(entity: BangumiBindingEntity, busy: Boolean, onOpen: () -> Unit, onUnlink: () -> Unit) {
    val value = remember(entity.data) { entity.binding() }
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(onClick = onOpen, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value.bookTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(value.subjectTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy) {
                        Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.bangumi_book_actions, value.bookTitle))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.bangumi_review_mapping)) }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.bangumi_unlink)) }, onClick = { menu = false; onUnlink() })
                    }
                }
            }
            Text(stringResource(R.string.bangumi_progress, value.target, value.remote))
            Text(stringResource(statusText(value.status)), style = MaterialTheme.typography.bodySmall)
            value.lastSyncedAt?.let { Text(stringResource(R.string.bangumi_last_sync, DateFormat.getDateTimeInstance().format(Date(it))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun MappingCard(row: BangumiVolumeMapping, all: List<BangumiVolumeMapping>, originalEditionKey: String?, baseline: Boolean, showBaseline: Boolean,
    enabled: Boolean, onMapping: (String, String?) -> Unit, onComplete: (String, Boolean) -> Unit, onBaseline: (String, Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Choice(row.title, row.editionKey != null, enabled) { checked ->
                onMapping(row.volumeId, if (checked) originalEditionKey ?: "local:${row.volumeId}" else null)
            }
            if (row.editionKey != null) {
                Choice(stringResource(R.string.bangumi_complete_volume), row.complete, enabled) { onComplete(row.volumeId, it) }
                if (showBaseline) Choice(stringResource(R.string.bangumi_already_read), baseline, enabled) { onBaseline(row.editionKey, it) }
                Box {
                    val first = all.first { it.editionKey == row.editionKey }
                    TextButton(onClick = { menu = true }, enabled = enabled) { Text(stringResource(R.string.bangumi_count_as, first.title)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.bangumi_separate_volume)) }, onClick = {
                            menu = false; onMapping(row.volumeId, "local:${row.volumeId}")
                        })
                        all.filter { it.editionKey != null && it.volumeId != row.volumeId }.distinctBy { it.editionKey }.forEach { other ->
                            DropdownMenuItem(text = { Text(other.title) }, onClick = { menu = false; onMapping(row.volumeId, other.editionKey) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

private fun statusText(status: BangumiSyncStatus): Int = when (status) {
    BangumiSyncStatus.READY -> R.string.bangumi_status_ready
    BangumiSyncStatus.PENDING -> R.string.bangumi_status_pending
    BangumiSyncStatus.SYNCED -> R.string.bangumi_status_synced
    BangumiSyncStatus.REMOTE_AHEAD -> R.string.bangumi_status_ahead
    BangumiSyncStatus.AUTH_REQUIRED -> R.string.bangumi_error_auth
    BangumiSyncStatus.REMOTE_CHANGED -> R.string.bangumi_status_conflict
    BangumiSyncStatus.MAPPING_CHANGED -> R.string.bangumi_status_mapping
    BangumiSyncStatus.REMOTE_STATE -> R.string.bangumi_status_state
    BangumiSyncStatus.REMOTE_MISSING -> R.string.bangumi_status_missing
    BangumiSyncStatus.REQUEST_REJECTED -> R.string.bangumi_status_rejected
    BangumiSyncStatus.OFFLINE -> R.string.bangumi_status_offline
}
