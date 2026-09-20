package indi.renakoni.nextvol.ui.book.reader.content

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/** A completed swipe turns one page without moving the page under the finger. */
@Composable
internal fun Modifier.readerPageSwipe(enabled: Boolean, gestureKey: Any? = null, onTurn: (forward: Boolean) -> Unit): Modifier {
    val selection = LocalReaderSelectionState.current
    val onTurnNow by rememberUpdatedState(onTurn)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    if (!enabled || selection.hasSelection) return this
    return pointerInput(rtl, gestureKey) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                if (abs(distance) >= viewConfiguration.touchSlop) onTurnNow((distance < 0f) != rtl)
                distance = 0f
            },
            onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
        )
    }
}
