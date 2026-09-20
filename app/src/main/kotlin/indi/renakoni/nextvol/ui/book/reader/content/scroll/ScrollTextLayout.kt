package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderTextLayoutInput
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragment
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragments
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextSource
import indi.renakoni.nextvol.ui.book.reader.content.componet.layoutReaderText
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

/** Full chapter geometry is cheap to scroll; only nearby fragments become Compose text nodes. */
internal class ScrollTextLayout(val fragments: List<ReaderTextFragment>, val style: TextStyle) {
    val offsets = IntArray(fragments.size + 1).also { offsets ->
        fragments.forEachIndexed { index, fragment ->
            offsets[index + 1] = offsets[index] + fragment.spacingBefore + fragment.height
        }
    }
    val height: Int get() = offsets.last()

    fun offsetFor(anchor: ReaderContentAnchor): Int? {
        val index = fragments.indexOfFirst { it.componentIndex == anchor.componentIndex && anchor.offset in it.start until it.end }
        if (index < 0) return null
        val fragment = fragments[index]
        val line = fragment.lineStarts.indexOfLast { it <= anchor.offset }.coerceAtLeast(0)
        return offsets[index] + fragment.spacingBefore + (fragment.lineTops.getOrNull(line) ?: 0)
    }

    fun visibleRange(top: Int, bottom: Int): IntRange {
        if (bottom <= top || bottom <= 0 || top >= height || fragments.isEmpty()) return IntRange.EMPTY
        fun indexAt(y: Int): Int {
            val found = offsets.binarySearch(y.coerceIn(0, height - 1))
            return (if (found >= 0) found else -found - 2).coerceIn(fragments.indices)
        }
        return indexAt(top)..indexAt(bottom - 1)
    }
}

internal class PreparedScrollChapter(
    val content: ChapterContentUiState,
    val text: Map<Int, ScrollTextLayout>,
) {
    // Positions inside the chapter include its title, images and component spacing.
    val componentOffsets = mutableStateMapOf<Int, Int>()
    fun offsetFor(anchor: ReaderContentAnchor): Int? {
        val top = componentOffsets[anchor.componentIndex] ?: return null
        return text[anchor.componentIndex]?.offsetFor(anchor)?.plus(top)
    }
}

/** Each job has its own measurer/cache. Cancellation never publishes a previous chapter's layout. */
@Composable
internal fun rememberPreparedScrollChapter(
    content: ChapterContentUiState?,
    layout: ReaderTextLayoutInput?,
    width: Int,
    viewportHeight: Int,
): PreparedScrollChapter? {
    if (content == null) return null
    if (layout == null) return remember(content) { PreparedScrollChapter(content, emptyMap()) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var prepared by remember(content.id) { mutableStateOf<PreparedScrollChapter?>(null) }
    LaunchedEffect(content, layout.settings, layout.style, density, direction, width, viewportHeight) {
        if (width <= 0 || viewportHeight <= 0) return@LaunchedEffect
        val result = withContext(Dispatchers.Default) {
            val measurer = TextMeasurer(createFontFamilyResolver(context), density, direction, cacheSize = 0)
            val cancellation = currentCoroutineContext()
            val text = content.content.mapIndexedNotNull { index, component ->
                if (component !is SimpleTextComponent) return@mapIndexedNotNull null
                cancellation.ensureActive()
                val fragments = layoutReaderText(
                    listOf(ReaderTextSource(index, component.data.text)), width, viewportHeight,
                    layout.paragraphSpacingPx, keepParagraphSpacingAtPageBreaks = true,
                ) { paragraph, maxWidth ->
                    cancellation.ensureActive()
                    measurer.measure(paragraph, layout.style, constraints = Constraints(maxWidth = maxWidth))
                }.flatten()
                index to ScrollTextLayout(fragments, layout.style)
            }.toMap()
            PreparedScrollChapter(content, text)
        }
        prepared = result
    }
    // Keep the old geometry visible while settings/content are being prepared. The chapter item
    // keeps its identity and offset; first entry remains a loading item until geometry is available.
    return prepared
}

@Composable
internal fun ScrollTextContent(layout: ScrollTextLayout, color: Color, modifier: Modifier) {
    var visible by remember(layout) { mutableStateOf(IntRange.EMPTY) }
    Layout(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            if (bounds.isEmpty) {
                visible = IntRange.EMPTY
            } else {
                val origin = coordinates.positionInWindow().y
                // One viewport on either side avoids a blank edge between placement callbacks.
                val overscan = bounds.height
                visible = layout.visibleRange(
                    floor(bounds.top - origin - overscan).toInt(),
                    ceil(bounds.bottom - origin + overscan).toInt(),
                )
            }
        },
        content = {
            if (!visible.isEmpty()) {
                ReaderTextFragments(layout.fragments.subList(visible.first, visible.last + 1),
                    layout.style, color, Modifier)
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(Constraints.fixedWidth(constraints.maxWidth))
        layout(constraints.maxWidth, layout.height) {
            if (!visible.isEmpty()) placeable?.placeRelative(0, layout.offsets[visible.first])
        }
    }
}
