package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
