package indi.renakoni.nextvol.ui.book.reader.bookmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderBookmarksSheet(
    bookmarks: List<ReadingBookmark>,
    busy: Boolean,
    notice: Int? = null,
    onAdd: () -> Unit,
    onJump: (ReadingBookmark) -> Unit,
    onDelete: (ReadingBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reader_bookmarks_title), Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.reader_bookmarks_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (notice != null) Text(stringResource(notice), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            FilledTonalButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reader_bookmarks_add))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (bookmarks.isEmpty()) item {
                Text(stringResource(R.string.reader_bookmarks_empty), Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(bookmarks, key = { it.id }) { bookmark ->
                Surface(onClick = { onJump(bookmark) }, shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(bookmark.chapterTitle, style = MaterialTheme.typography.titleSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.reader_bookmarks_position, (bookmark.progress * 100).roundToInt()),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(bookmark.preview.ifBlank { stringResource(R.string.reader_bookmarks_illustration) },
                                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onDelete(bookmark) }, enabled = !busy) {
                            Icon(painterResource(R.drawable.delete_forever_24px),
                                stringResource(R.string.reader_bookmarks_delete, bookmark.chapterTitle))
                        }
                    }
                }
            }
        }
    }
}
