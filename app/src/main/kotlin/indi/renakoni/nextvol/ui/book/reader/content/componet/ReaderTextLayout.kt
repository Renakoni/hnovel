package indi.renakoni.nextvol.ui.book.reader.content.componet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSelectionState
import kotlin.math.ceil

internal data class ReaderTextSource(val componentIndex: Int, val text: String)

internal data class ReaderTextFragment(
    val componentIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val spacingBefore: Int,
    val height: Int,
)

/** Legacy simple_text uses LF/CRLF as paragraph boundaries; U+2028 remains a soft break.
 * Empty paragraphs are explicit blank lines, and a terminal separator adds no phantom paragraph.
 */
internal fun layoutReaderText(
    sources: List<ReaderTextSource>,
    width: Int,
    height: Int,
    paragraphSpacing: Int,
    measure: (String, Int) -> TextLayoutResult,
): List<List<ReaderTextFragment>> {
    val pages = mutableListOf<List<ReaderTextFragment>>()
    var page = mutableListOf<ReaderTextFragment>()
    var usedHeight = 0
    val safeHeight = height.coerceAtLeast(1)
    fun nextPage() {
        if (page.isNotEmpty()) pages += page.toList()
        page = mutableListOf()
        usedHeight = 0
    }
    sources.forEach { source ->
        var paragraphStart = 0
        while (paragraphStart < source.text.length) {
            val newline = source.text.indexOf('\n', paragraphStart)
            val paragraphEnd = if (newline < 0) source.text.length else newline
            val contentEnd = if (newline >= 0 && paragraphEnd > paragraphStart && source.text[paragraphEnd - 1] == '\r')
                paragraphEnd - 1 else paragraphEnd
            val nextStart = if (newline < 0) source.text.length else newline + 1
            val paragraph = source.text.substring(paragraphStart, contentEnd)
            val measured = measure(paragraph, width.coerceAtLeast(1))
            var firstLine = 0
            while (firstLine < measured.lineCount) {
                var spacing = if (firstLine == 0 && page.isNotEmpty()) paragraphSpacing else 0
                val top = measured.getLineTop(firstLine)
                fun lineHeight(lastLine: Int) = ceil(measured.getLineBottom(lastLine) - top).toInt().coerceAtLeast(1)
                if (page.isNotEmpty() && lineHeight(firstLine) + spacing > safeHeight - usedHeight) {
                    nextPage()
                    spacing = 0
                }
                var lastLine = firstLine
                while (lastLine + 1 < measured.lineCount &&
                    lineHeight(lastLine + 1) <= safeHeight - usedHeight - spacing
                ) lastLine++
                // Even a viewport shorter than one line consumes a complete line.
                val start = measured.getLineStart(firstLine)
                val end = if (lastLine == measured.lineCount - 1) paragraph.length
                    else measured.getLineStart(lastLine + 1)
                val fragmentHeight = lineHeight(lastLine)
                page += ReaderTextFragment(
                    source.componentIndex,
                    paragraphStart + start,
                    if (lastLine == measured.lineCount - 1) nextStart else paragraphStart + end,
                    paragraph.substring(start, end).let {
                        if (lastLine < measured.lineCount - 1) it.removeSuffix("\u2028") else it
                    }, spacing, fragmentHeight,
                )
                usedHeight += spacing + fragmentHeight
                firstLine = lastLine + 1
                if (firstLine < measured.lineCount) nextPage()
            }
            paragraphStart = nextStart
        }
    }
    nextPage()
    return pages
}

internal fun layoutReaderText(
    sources: List<ReaderTextSource>, width: Int, height: Int, paragraphSpacing: Int,
    style: TextStyle, measurer: TextMeasurer,
): List<List<ReaderTextFragment>> = layoutReaderText(sources, width, height, paragraphSpacing) { text, maxWidth ->
    measurer.measure(text, style, constraints = Constraints(maxWidth = maxWidth))
}

@Composable
internal fun ReaderTextFragments(
    fragments: List<ReaderTextFragment>, style: TextStyle, color: Color, modifier: Modifier,
) {
    val readerSelection = LocalReaderSelectionState.current
    val selectionState = rememberSelectionState()
    val density = LocalDensity.current
    DisposableEffect(readerSelection, selectionState) {
        readerSelection.register(selectionState)
        onDispose { readerSelection.unregister(selectionState) }
    }
    SelectionContainer(state = selectionState) {
        Column(modifier) {
            fragments.forEach { fragment ->
                if (fragment.spacingBefore > 0) Spacer(Modifier.height(with(density) { fragment.spacingBefore.toDp() }))
                Text(
                    text = fragment.text, style = style, color = color,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
