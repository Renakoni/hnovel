package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import android.app.Application
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollProgressTimingTest {
    private val env = ModeTestEnvironment()
    private var now = 10_000L
    private val offset = mutableIntStateOf(0)
    private val scrolling = mutableStateOf(true)
    private val writes = mutableListOf<Pair<String, Float>>()
    private val item = mockk<LazyListItemInfo> {
        every { key } returns "chapter"
        every { size } returns 1000
        every { offset } answers { -this@ScrollProgressTimingTest.offset.intValue }
    }
    private val layout = mockk<LazyListLayoutInfo> { every { visibleItemsInfo } returns listOf(item) }
    private val uiState = MutableScrollContentUiSate({}, {}, {}, {}, {}).apply {
        readingChapterId = "chapter"
        lazyListState = mockk<LazyListState> {
            every { firstVisibleItemScrollOffset } answers { offset.intValue }
            every { isScrollInProgress } answers { scrolling.value }
            every { layoutInfo } returns layout
        }
    }
    private val progress = ScrollReadingProgress(
        uiState, env.scope, { id, value -> writes += id to value }, { 100 }, env.dispatcher, env.dispatcher, { now },
    )

    @After fun tearDown() = env.close()

    @Test
    fun observationAndPersistenceKeepTheirSeparate120And2500MillisecondWindows() {
        progress.start()
        env.runCurrent()
        assertEquals(listOf("chapter" to 0.1f), writes)
        move(10_050, 100)
        assertEquals(0.1f, uiState.readingProgress)
        move(10_120, 200)
        assertEquals(0.3f, uiState.readingProgress)
        assertEquals(1, writes.size)
        move(12_500, 300)
        assertEquals(listOf("chapter" to 0.1f, "chapter" to 0.4f), writes)
        move(12_620, 1000)
        assertEquals("chapter" to 1f, writes.last())
        assertEquals(3, writes.size)
        scrolling.value = false
        env.runCurrent()
        assertEquals(4, writes.size)
        assertEquals("chapter" to 1f, writes.last())
    }

    @Test
    fun pendingThrottledOffsetsDoNotEmitOnTimeAloneButStoppingRecalculatesProgress() {
        progress.start()
        env.runCurrent()
        move(10_050, 100)
        now = 20_000
        env.scheduler.advanceTimeBy(9_950)
        env.runCurrent()
        assertEquals(0.1f, uiState.readingProgress)
        assertEquals(1, writes.size)
        scrolling.value = false
        env.runCurrent()
        assertEquals(0.2f, uiState.readingProgress)
        assertEquals(listOf("chapter" to 0.1f, "chapter" to 0.2f), writes)
    }

    private fun move(time: Long, pixels: Int) {
        now = time
        offset.intValue = pixels
        env.runCurrent()
    }
}
