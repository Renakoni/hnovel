package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

internal class ReaderSelectionState {
    private val selections = mutableSetOf<SelectionState>()

    val hasSelection: Boolean
        get() = selections.any { state -> state.selectedTexts.any { it.isNotEmpty() } }

    fun register(state: SelectionState) {
        selections.add(state)
    }

    fun unregister(state: SelectionState) {
        selections.remove(state)
    }

    fun clear() {
        selections.filter { state -> state.selectedTexts.any { it.isNotEmpty() } }
            .forEach { it.clear() }
    }
}

internal val LocalReaderSelectionState = staticCompositionLocalOf { ReaderSelectionState() }

@Composable
internal fun Modifier.readerTapGestures(onTap: (Offset) -> Unit): Modifier {
    val selectionState = LocalReaderSelectionState.current
    val currentOnTap by rememberUpdatedState(onTap)
    return pointerInput(selectionState) {
        awaitEachGesture {
            // Read selection at DOWN, before child text can change it for this gesture.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (selectionState.hasSelection) {
                // Let drags and long presses retain normal selection/scroll behavior.
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation(PointerEventPass.Initial)
                }
                if (up != null) {
                    // Clear before the text handles UP and refreshes its selection toolbar.
                    up.consume()
                    selectionState.clear()
                }
            } else {
                val up = waitForUpOrCancellation()
                if (up != null) {
                    up.consume()
                    currentOnTap(down.position)
                }
            }
        }
    }
}
