package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.util.Log
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

internal enum class ReaderSystemBarMode {
    Hidden,
    StatusBarOnly,
    Visible,
}

internal fun readerSystemBarMode(
    immersive: Boolean,
    enableHideStatusBar: Boolean,
): ReaderSystemBarMode = when {
    !immersive -> ReaderSystemBarMode.Visible
    enableHideStatusBar -> ReaderSystemBarMode.Hidden
    else -> ReaderSystemBarMode.StatusBarOnly
}

@Composable
internal fun ReaderWindowEffects(
    window: Window,
    immersive: Boolean,
    enableHideStatusBar: Boolean,
    batteryIndicatorDisplayMode: String,
    keepScreenOn: Boolean,
) {
    ReaderDebugProbe(window = window, immersive = immersive)
    val originalUiFlags = remember {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility
    }

    LaunchedEffect(immersive, enableHideStatusBar, batteryIndicatorDisplayMode) {
        updateReaderImmersiveMode(
            window = window,
            immersive = immersive,
            enableHideStatusBar = enableHideStatusBar,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        @Suppress("deprecation")
        onDispose {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.decorView.systemUiVisibility = originalUiFlags
        }
    }

    LaunchedEffect(keepScreenOn) {
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
internal fun ReaderReadingTimeEffects(
    readingScreenUiState: ReaderScreenUiState,
    updateTotalReadingTime: (bookId: String, Int) -> Unit,
    accumulateReadTime: (bookId: String, Int) -> Unit,
) {
    var isRunning by remember { mutableStateOf(false) }
    var totalReadingTime by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        isRunning = true
        onPauseOrDispose {
            isRunning = false
            if (totalReadingTime <= 60) {
                readingScreenUiState.bookId?.let {
                    updateTotalReadingTime(it, totalReadingTime)
                }
            } else {
                Log.e("ReaderScreen", "time counter error, time now is $totalReadingTime over 60s")
            }
            totalReadingTime = 0
        }
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            totalReadingTime += 1
            if (totalReadingTime > 60) {
                readingScreenUiState.bookId?.let {
                    updateTotalReadingTime(it, totalReadingTime)
                }
                totalReadingTime = 0
            }
            delay(1.seconds)
        }
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            readingScreenUiState.bookId?.let {
                accumulateReadTime(it, 1)
            }
            delay(1.seconds)
        }
    }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            readingScreenUiState.bookId?.let {
                accumulateReadTime(it, -1)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (totalReadingTime <= 60) {
                readingScreenUiState.bookId?.let {
                    updateTotalReadingTime(it, totalReadingTime)
                }
            } else {
                Log.e("ReaderScreen", "time counter error, time now is $totalReadingTime over 60s")
            }
        }
    }
}

private fun updateReaderImmersiveMode(
    window: Window,
    immersive: Boolean,
    enableHideStatusBar: Boolean,
) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)

    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    when (readerSystemBarMode(immersive, enableHideStatusBar)) {
        ReaderSystemBarMode.Hidden -> {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        ReaderSystemBarMode.StatusBarOnly -> {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }

        ReaderSystemBarMode.Visible -> {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
