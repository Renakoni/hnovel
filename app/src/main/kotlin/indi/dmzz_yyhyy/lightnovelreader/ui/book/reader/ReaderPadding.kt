package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun readerAutoPadding(indicatorHeight: Dp): PaddingValues {
    // Controls overlay the page. System bar visibility must not resize its viewport either.
    val insets = WindowInsets.systemBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        top = insets.calculateTopPadding(),
        bottom = insets.calculateBottomPadding() + indicatorHeight,
        start = insets.calculateStartPadding(direction) + 16.dp,
        end = insets.calculateEndPadding(direction) + 16.dp
    )
}
