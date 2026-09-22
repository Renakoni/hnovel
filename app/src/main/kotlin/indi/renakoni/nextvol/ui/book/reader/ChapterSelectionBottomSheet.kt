package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.Loading
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSelectionBottomSheet(
    sheetState: SheetState,
    selectedVolumeId: String,
    bookVolumes: BookVolumes,
    readingChapterId: String,
    onDismissRequest: () -> Unit,
    onClickChapter: (chapterId: String) -> Unit,
    onChangeSelectedVolumeId: (volumeId: String) -> Unit
) {
    val lazyColumnState = rememberLazyListState()
    var autoScrolled by rememberSaveable(bookVolumes.bookId, readingChapterId) { mutableStateOf(false) }

    LaunchedEffect(sheetState.currentValue, readingChapterId, bookVolumes, selectedVolumeId, autoScrolled) {
        if (autoScrolled || sheetState.currentValue == SheetValue.Hidden || readingChapterId.isBlank()) return@LaunchedEffect
        val volumeIndex = bookVolumes.volumes.indexOfFirst { volume -> volume.chapters.any { it.id == readingChapterId } }
        if (volumeIndex < 0) return@LaunchedEffect
        val volume = bookVolumes.volumes[volumeIndex]
        if (selectedVolumeId != volume.volumeId) {
            onChangeSelectedVolumeId(volume.volumeId)
            return@LaunchedEffect // Wait for the expanded chapter items to enter the list.
        }
        val chapterIndex = volume.chapters.indexOfFirst { it.id == readingChapterId }
        lazyColumnState.scrollToItem(volumeIndex + 1 + chapterIndex)
        autoScrolled = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = colorScheme.surface.luminance() > 0.5f,
        ),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.read_more_24px), contentDescription = null)
                Text(stringResource(R.string.select_chapter), style = typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (bookVolumes.volumes.all { it.chapters.isEmpty() }) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Loading() }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = lazyColumnState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    bookVolumes.volumes.forEach { volume ->
                        val expanded = selectedVolumeId == volume.volumeId
                        item(key = "volume:${volume.volumeId}", contentType = "volume") {
                            DirectoryVolumeRow(volume, expanded) {
                                onChangeSelectedVolumeId(if (expanded) "" else volume.volumeId)
                            }
                        }
                        if (expanded) items(
                            volume.chapters,
                            key = { "chapter:${volume.volumeId.length}:${volume.volumeId}:${it.id}" },
                            contentType = { "chapter" },
                        ) { chapter ->
                            DirectoryChapterRow(chapter, readingChapterId == chapter.id) { onClickChapter(chapter.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryVolumeRow(volume: Volume, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(
            role = Role.Button,
            onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand),
            onClick = onClick,
        ).padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(volume.volumeTitle, style = typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.info_volume_chapters_count, volume.chapters.size),
                style = typography.labelMedium, color = colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.arrow_forward_ios_24px), contentDescription = null,
            modifier = Modifier.size(16.dp).rotate(if (expanded) -90f else 90f), tint = colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DirectoryChapterRow(chapter: ChapterInformation, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colorScheme.secondaryContainer else Color.Transparent,
    ) {
        Row(
            Modifier.fillMaxWidth().selectable(selected, role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) Icon(painterResource(R.drawable.play_arrow_24px), contentDescription = null,
                modifier = Modifier.size(20.dp), tint = colorScheme.onSecondaryContainer)
            Text(chapter.title, style = typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
