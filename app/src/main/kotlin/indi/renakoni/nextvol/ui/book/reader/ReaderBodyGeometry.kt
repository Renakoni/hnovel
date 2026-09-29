package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.ui.book.reader.content.ReaderMode
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle

@Composable
internal fun resolveReaderBodyLayout(
    hostSize: IntSize,
    padding: PaddingValues,
    mode: ReaderMode,
    preference: String = "auto",
    supportsDoublePage: Boolean = false,
): ReaderLayoutResult {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val style = LocalReaderTextLayout.current?.style
    val fallback = LocalReaderStyle.current
    return with(density) {
        resolveReaderLayout(ReaderLayoutInput(
            hostSize = hostSize,
            density = density.density,
            padding = ReaderPaddingPx(
                padding.calculateStartPadding(direction).roundToPx(),
                padding.calculateEndPadding(direction).roundToPx(),
                padding.calculateTopPadding().roundToPx(),
                padding.calculateBottomPadding().roundToPx(),
            ),
            fontSizePx = (style?.fontSize ?: fallback.fontSize.sp).toPx(),
            lineHeightPx = (style?.lineHeight ?: (fallback.fontSize + fallback.fontLineHeight).sp).toPx(),
            mode = mode,
            preference = preference,
            supportsDoublePage = supportsDoublePage,
        ))
    }
}

/** Keep the host full-size, but measure and place its body with the pagination's exact pixels. */
internal fun Modifier.readerBodyGeometry(geometry: ReaderBodyGeometry): Modifier = layout { measurable, constraints ->
    val body = measurable.measure(Constraints.fixed(geometry.widthPx, geometry.leafSize.height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        body.placeRelative(geometry.startPx, geometry.topPx)
    }
}
