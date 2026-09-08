package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ReaderReadingTimeEffectsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var activity: ActivityController<ComponentActivity>
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private val visible = mutableStateOf(true)
    private val state = MutableReaderScreenUiState(null).apply { bookId = "book" }
    private val calls = mutableListOf<Call>()
    private var elapsedRealtime = 0L

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        activity.get().setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) {
                    ReaderReadingTimeEffects(
                        currentBookId = { state.bookId },
                        updateTotalReadingTime = { id, seconds -> calls += Call("total", id, seconds) },
                        accumulateReadTime = { id, seconds -> calls += Call("stats", id, seconds) },
                        nowMillis = { elapsedRealtime },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100, ignoreFrameDuration = true)
        compose.waitForIdle()
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(100, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun resumeDoesNotInventTimeAndDelayedTicksRecordElapsedSeconds() {
        compose.runOnIdle { assertEquals(emptyList<Call>(), calls) }
        elapsedRealtime = 3_500
        compose.mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)
        compose.waitForIdle()

        assertEquals(listOf(Call("stats", "book", 3)), calls.filter { it.channel == "stats" })
        assertEquals(emptyList<Call>(), calls.filter { it.channel == "total" })

        pause()
        compose.runOnIdle {
            assertEquals(
                listOf(Call("stats", "book", 3), Call("stats", "book", -1)),
                calls.filter { it.channel == "stats" },
            )
            assertEquals(listOf(Call("total", "book", 3)), calls.filter { it.channel == "total" })
        }
    }

    @Test
    fun pauseFlushesBeforeSettlementAndResumeStartsANewCounter() {
        advanceReadingTime(2_500)
        pause()
        compose.runOnIdle {
            assertEquals(
                listOf(Call("stats", "book", 2), Call("stats", "book", -1)),
                calls.filter { it.channel == "stats" },
            )
            assertEquals(listOf(Call("total", "book", 2)), calls.filter { it.channel == "total" })
        }
        compose.mainClock.advanceTimeBy(10_000, ignoreFrameDuration = true)
        compose.runOnIdle { assertEquals(3, calls.size) }
        elapsedRealtime += 100
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        pause()
        compose.runOnIdle {
            assertEquals(
                listOf(Call("stats", "book", -1)),
                calls.filter { it.channel == "stats" }.takeLast(1),
            )
            assertEquals(listOf(Call("total", "book", 0)), calls.filter { it.channel == "total" }.takeLast(1))
        }
    }

    @Test
    fun leavingWhileResumedDoesNotInventASecond() {
        removeReader()
        compose.runOnIdle {
            assertEquals(emptyList<Call>(), calls.filter { it.seconds > 0 })
        }
    }

    @Test
    fun leavingAfterPauseDoesNotInventASecond() {
        pause()
        removeReader()
        compose.runOnIdle {
            assertEquals(emptyList<Call>(), calls.filter { it.seconds > 0 })
        }
    }

    @Test
    fun settlementReadsCurrentBookAndNullBookSuppressesCallbacks() {
        compose.runOnIdle { state.bookId = "next" }
        pause()
        compose.runOnIdle {
            assertEquals(listOf(Call("stats", "next", -1)), calls.filter { it.channel == "stats" })
            assertEquals(listOf(Call("total", "next", 0)), calls.filter { it.channel == "total" }.takeLast(1))
            state.bookId = null
        }
        removeReader()
        compose.runOnIdle { assertEquals(2, calls.size) }
    }

    private fun pause() {
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(100, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    private fun advanceReadingTime(milliseconds: Long) {
        elapsedRealtime += milliseconds
        compose.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
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

    private data class Call(val channel: String, val bookId: String, val seconds: Int)
}
