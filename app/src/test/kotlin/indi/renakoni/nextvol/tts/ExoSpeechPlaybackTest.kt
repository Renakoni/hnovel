package indi.renakoni.nextvol.tts

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExoSpeechPlaybackTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun boundariesUseTheMediaClockAndNeverRestartAudioOrAdvanceWhilePaused() = runTest {
        var playing = false
        var position = 0L
        val listener = slot<Player.Listener>()
        val player = mockk<ExoPlayer>(relaxed = true) {
            every { isPlaying } answers { playing }
            every { currentPosition } answers { position }
            every { playbackState } returns Player.STATE_READY
            every { duration } returns 6000L
            every { addListener(capture(listener)) } returns Unit
        }
        val playback = ExoSpeechPlayback(player, RuntimeEnvironment.getApplication())
        val timing = listOf(SpeechTiming(100, 0, 3), SpeechTiming(1000, 3, 6))
        val clip = SpeechClip(SpeechChapter("book", "chapter", "Book", "Chapter", "你好。再见。"),
            SpeechSegment(0, 6, "你好。再见。"), 0, 1, files.newFile(), timing)
        val delivered = mutableListOf<SpeechTiming>()
        val estimates = mutableListOf<Int>()
        val job = launch { playback.play(clip, true, { delivered += it }, { estimates += it }) { _, _ -> } }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(delivered.isEmpty())
        playing = true
        position = 100
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(timing[0]), delivered)
        // Advancing wall time without advancing the audio cannot select the next boundary.
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf(timing[0]), delivered)
        playing = false
        position = 1000
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(timing[0]), delivered)
        playing = true
        advanceTimeBy(50)
        runCurrent()
        assertEquals(timing, delivered)
        playing = false
        position = 0
        listener.captured.onPositionDiscontinuity(mockk(), mockk(), Player.DISCONTINUITY_REASON_SEEK)
        advanceTimeBy(50)
        runCurrent()
        assertEquals("Paused seek before the first timestamp must restore the first range", timing + timing.first(), delivered)
        verify(exactly = 1) { player.setMediaItem(any<MediaItem>()) }
        verify(exactly = 1) { player.prepare() }
        playback.stop()
        runCurrent()
        assertTrue(job.isCancelled)
        advanceTimeBy(500)
        assertEquals(timing + timing.first(), delivered)
        assertTrue("Real boundaries must take precedence over proportional following", estimates.isEmpty())
    }

    @Test fun audioOnlyFollowingFreezesWithoutPlaybackButHandlesExplicitSeeksWhilePaused() = runTest {
        var playing = true
        var position = 0L
        var duration = -1L
        var state = Player.STATE_READY
        val listener = slot<Player.Listener>()
        val player = mockk<ExoPlayer>(relaxed = true) {
            every { isPlaying } answers { playing }
            every { currentPosition } answers { position }
            every { this@mockk.duration } answers { duration }
            every { playbackState } answers { state }
            every { addListener(capture(listener)) } returns Unit
        }
        val playback = ExoSpeechPlayback(player, RuntimeEnvironment.getApplication())
        val text = "甲乙丙丁戊己庚辛壬癸"
        val clip = SpeechClip(SpeechChapter("book", "chapter", "Book", "Chapter", text),
            SpeechSegment(0, text.length, text), 0, 1, files.newFile())
        val anchors = mutableListOf<Int>()
        val ranges = mutableListOf<SpeechTiming>()
        val job = launch { playback.play(clip, true, { ranges += it }, { anchors += it }) { _, _ -> } }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(anchors.isEmpty())
        duration = 1000
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(0), anchors)
        position = 500
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(0, 5), anchors)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(0, 5), anchors)
        playing = false
        position = 800
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(0, 5), anchors)
        state = Player.STATE_BUFFERING
        listener.captured.onPositionDiscontinuity(mockk(), mockk(), Player.DISCONTINUITY_REASON_SEEK)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(0, 5), anchors)
        state = Player.STATE_READY
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(0, 5, 8), anchors)
        position = 100
        listener.captured.onPositionDiscontinuity(mockk(), mockk(), Player.DISCONTINUITY_REASON_SEEK)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(0, 5, 8, 1), anchors)
        playing = true
        position = 1500
        advanceTimeBy(50)
        runCurrent()
        assertEquals(9, anchors.last())
        assertTrue("Estimated viewport anchors must not invent exact word ranges", ranges.isEmpty())
        verify(exactly = 1) { player.setMediaItem(any<MediaItem>()) }
        verify(exactly = 1) { player.prepare() }
        playback.stop()
        runCurrent()
        assertTrue(job.isCancelled)
        val stopped = anchors.toList()
        advanceTimeBy(500)
        assertEquals(stopped, anchors)
    }
}
