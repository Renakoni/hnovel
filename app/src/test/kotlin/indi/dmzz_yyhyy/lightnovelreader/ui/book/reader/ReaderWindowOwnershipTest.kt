package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.WindowCompat
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Characterizes ownership conflicts on the legacy system-UI path, not physical display frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ReaderWindowOwnershipTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val visible = mutableStateOf(true)

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        @Suppress("DEPRECATION")
        activity.get().window.decorView.systemUiVisibility = 0
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun disposalRestoresEntryAppearanceEvenAfterAnotherOwnerChangesIt() {
        mountReader()
        val window = activity.get().window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        compose.runOnIdle {
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        }
        removeReader()
        compose.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
    }

    @Test
    fun disposalClearsAKeepScreenOnFlagThatExistedBeforeTheReader() {
        val window = activity.get().window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mountReader()
        compose.runOnIdle {
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
        removeReader()
        compose.runOnIdle {
            assertFalse(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
    }

    private fun mountReader() {
        activity.get().setContent {
            if (visible.value) {
                ReaderWindowEffects(
                    window = activity.get().window,
                    immersive = true,
                    enableHideStatusBar = true,
                    batteryIndicatorDisplayMode = "classic",
                    keepScreenOn = true,
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    private fun removeReader() {
        compose.runOnIdle {
            visible.value = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(100, ignoreFrameDuration = true)
        compose.waitForIdle()
    }
}
