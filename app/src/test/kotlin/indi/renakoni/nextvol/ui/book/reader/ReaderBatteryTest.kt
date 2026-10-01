package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderBatteryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var context: BatteryContext
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private val visible = mutableStateOf(true)
    private val progress = mutableStateOf(0f)

    @Before fun open() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        context = BatteryContext(activity.get())
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
    }

    @After fun close() { activity.pause().stop().destroy() }

    private fun mount(indicator: Boolean = false) {
        activity.get().setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner,
                    LocalReduceReaderMotion provides true) {
                    if (indicator) Indicator(enableBatteryIndicator = visible.value, enableTimeIndicator = false,
                        enableChapterTitle = false, chapterTitle = "", enableReadingChapterProgressIndicator = true,
                        readingChapterProgress = progress.value)
                    else if (visible.value) Text("battery:${rememberReaderBatteryLevel().value}")
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun progressRecompositionDoesNotQueryTheHealthServiceOrResubscribe() {
        mount(indicator = true)
        compose.waitUntil(5_000) { context.registered.get() == 1 }
        repeat(12) { value ->
            compose.runOnIdle { progress.value = value / 20f }
            compose.waitForIdle()
        }
        assertEquals(0, context.batteryServiceQueries.get())
        assertEquals(1, context.registered.get())
        assertTrue(context.binderThreads.none { it === Looper.getMainLooper().thread })
    }

    @Test fun broadcastsConvertScaleAndInvalidValuesRemainUnknown() {
        context.sticky = battery(3, 4)
        mount()
        compose.waitUntil(5_000) { context.registered.get() == 1 }
        awaitValue("75")
        listOf(battery(-1, 100), battery(20, 0), battery(101, 100), Intent(Intent.ACTION_BATTERY_CHANGED)).forEach {
            compose.runOnIdle { context.receiver!!.onReceive(context, it) }
            awaitValue("null")
        }
        compose.runOnIdle { context.receiver!!.onReceive(context, battery(0, 100)) }
        awaitValue("0")
    }

    @Test fun hiddenAndStoppedReadersReleaseSubscriptionAndResumeRefreshesIt() {
        mount()
        compose.waitUntil(5_000) { context.registered.get() == 1 }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.waitUntil(5_000) { context.unregistered.get() == 1 }
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        compose.waitUntil(5_000) { context.registered.get() == 2 }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { context.unregistered.get() == 2 }
        context.sticky = battery(42, 100)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5_000) { context.registered.get() == 3 }
        awaitValue("42")
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        compose.waitUntil(5_000) { context.unregistered.get() == 3 }
        assertTrue(context.binderThreads.none { it === Looper.getMainLooper().thread })
    }

    private fun awaitValue(value: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("battery:$value")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("battery:$value").assertIsDisplayed()
    }

    private class BatteryContext(base: Context) : ContextWrapper(base) {
        val registered = AtomicInteger()
        val unregistered = AtomicInteger()
        val batteryServiceQueries = AtomicInteger()
        val binderThreads = CopyOnWriteArrayList<Thread>()
        @Volatile var receiver: BroadcastReceiver? = null
        @Volatile var sticky = battery(73, 100)
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name == BATTERY_SERVICE) batteryServiceQueries.incrementAndGet()
            return super.getSystemService(name)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
            broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent {
            assertTrue(filter.hasAction(Intent.ACTION_BATTERY_CHANGED))
            binderThreads += Thread.currentThread()
            this.receiver = receiver
            registered.incrementAndGet()
            val initial = sticky
            Handler(Looper.getMainLooper()).post { receiver?.onReceive(this, initial) }
            return sticky
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            assertSame(this.receiver, receiver)
            binderThreads += Thread.currentThread()
            this.receiver = null
            unregistered.incrementAndGet()
        }
    }

    companion object {
        private fun battery(level: Int, scale: Int) = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, scale)
    }
}
