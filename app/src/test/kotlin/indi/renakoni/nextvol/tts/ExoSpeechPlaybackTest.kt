package indi.renakoni.nextvol.tts

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
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
        val player = mockk<ExoPlayer>(relaxed = true) {
            every { isPlaying } answers { playing }
            every { currentPosition } answers { position }
        }
        val playback = ExoSpeechPlayback(player, RuntimeEnvironment.getApplication())
        val timing = listOf(SpeechTiming(100, 0, 3), SpeechTiming(1000, 3, 6))
        val clip = SpeechClip(SpeechChapter("book", "chapter", "Book", "Chapter", "你好。再见。"),
            SpeechSegment(0, 6, "你好。再见。"), 0, 1, files.newFile(), timing)
        val delivered = mutableListOf<SpeechTiming>()
        val job = launch { playback.play(clip, true, { delivered += it }) { _, _ -> } }
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
        verify(exactly = 1) { player.setMediaItem(any<MediaItem>()) }
        verify(exactly = 1) { player.prepare() }
        playback.stop()
        runCurrent()
        assertTrue(job.isCancelled)
        advanceTimeBy(500)
        assertEquals(timing, delivered)
    }
}
