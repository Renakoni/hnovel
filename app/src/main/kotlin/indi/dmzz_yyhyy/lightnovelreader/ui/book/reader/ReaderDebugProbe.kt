package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ReaderProbe"
private const val FRAMES_PER_TRANSITION = 12

@Composable
internal fun ReaderDebugProbe(window: Window, immersive: Boolean) {
    if (!BuildConfig.DEBUG) return

    val sequence = remember { AtomicInteger(0) }
    val framesRemaining = remember { AtomicInteger(0) }
    val decorView = window.decorView

    LaunchedEffect(immersive) {
        val id = sequence.incrementAndGet()
        framesRemaining.set(FRAMES_PER_TRANSITION)
        Log.i(
            TAG,
            "transition seq=$id immersive=$immersive uptime=${SystemClock.uptimeMillis()}"
        )
    }

    DisposableEffect(decorView) {
        val listener = ViewTreeObserver.OnPreDrawListener {
            val remaining = framesRemaining.getAndDecrement()
            if (remaining > 0) {
                val insets = ViewCompat.getRootWindowInsets(decorView)
                val systemBars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
                val stableSystemBars =
                    insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                @Suppress("DEPRECATION")
                val flags = decorView.systemUiVisibility
                Log.i(
                    TAG,
                    "frame seq=${sequence.get()} index=${FRAMES_PER_TRANSITION - remaining + 1} " +
                        "uptime=${SystemClock.uptimeMillis()} " +
                        "root=${decorView.width}x${decorView.height} " +
                        "bars=${systemBars?.left},${systemBars?.top},${systemBars?.right},${systemBars?.bottom} " +
                        "stable=${stableSystemBars?.left},${stableSystemBars?.top}," +
                        "${stableSystemBars?.right},${stableSystemBars?.bottom} " +
                        "visible=${insets?.isVisible(WindowInsetsCompat.Type.systemBars())} " +
                        "flags=0x${flags.toString(16)}"
                )
            }
            true
        }
        decorView.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose {
            if (decorView.viewTreeObserver.isAlive) {
                decorView.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
    }
}

internal fun Modifier.readerProbeLayout(label: String): Modifier {
    if (!BuildConfig.DEBUG) return this
    return onGloballyPositioned { coordinates ->
        logLayout(label, coordinates)
    }
}

private fun logLayout(label: String, coordinates: LayoutCoordinates) {
    val bounds = coordinates.boundsInWindow()
    Log.i(
        TAG,
        "layout label=$label uptime=${SystemClock.uptimeMillis()} " +
            "x=${bounds.left} y=${bounds.top} w=${bounds.width} h=${bounds.height}"
    )
}
