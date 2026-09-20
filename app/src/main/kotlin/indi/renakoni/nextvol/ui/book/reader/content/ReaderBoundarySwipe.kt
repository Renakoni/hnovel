package indi.renakoni.nextvol.ui.book.reader.content

import android.view.MotionEvent
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/** Observes the completed edge gesture without consuming the pager's pointer events. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.readerBoundarySwipe(
    pager: PagerState,
    enabled: Boolean,
    onTurn: (forward: Boolean) -> Unit,
): Modifier {
    val selection = LocalReaderSelectionState.current
    val onTurnNow by rememberUpdatedState(onTurn)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slop = LocalViewConfiguration.current.touchSlop
    val gesture = remember(pager, enabled, rtl, selection.hasSelection) { BoundaryGesture() }
    if (!enabled || selection.hasSelection) return this
    return motionEventSpy { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.active = !pager.isScrollInProgress && pager.pageCount > 0 &&
                    abs(pager.currentPageOffsetFraction) < 0.001f
                gesture.atStart = pager.currentPage == 0
                gesture.atEnd = pager.currentPage == pager.pageCount - 1
                gesture.x = event.x
                gesture.y = event.y
                gesture.horizontal = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> gesture.active = false
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - gesture.x)
                val dy = abs(event.y - gesture.y)
                if (!gesture.horizontal && maxOf(dx, dy) > slop) {
                    gesture.horizontal = dx > dy
                    if (!gesture.horizontal) gesture.active = false
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - gesture.x
                val dy = event.y - gesture.y
                val forward = (dx < 0f) != rtl
                val turn = gesture.active && abs(dx) > slop && abs(dx) > abs(dy) &&
                    (if (forward) gesture.atEnd else gesture.atStart)
                gesture.active = false
                if (turn) onTurnNow(forward)
            }
        }
    }
}

private class BoundaryGesture {
    var active = false
    var atStart = false
    var atEnd = false
    var horizontal = false
    var x = 0f
    var y = 0f
}
