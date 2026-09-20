package indi.renakoni.nextvol.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechTimingTest {
    @Test fun fileSynthesisFramesAreReadOnlyWhenPlaybackReachesThem() {
        val timing = SpeechTimingCollector("你好。再见。")
        timing.begin(24000)
        timing.range(2400, 0, 3)
        timing.range(48000, 3, 6)
        val ranges = timing.finish()
        assertNull(ranges.rangeAt(99))
        assertEquals(SpeechTiming(100, 0, 3), ranges.rangeAt(100))
        assertEquals(SpeechTiming(100, 0, 3), ranges.rangeAt(1999))
        assertEquals(SpeechTiming(2000, 3, 6), ranges.rangeAt(2000))
    }

    @Test fun missingOrMalformedMetadataFallsBackToTheWholeClip() {
        assertTrue(SpeechTimingCollector("正文").finish().isEmpty())
        for (invalid in listOf(Triple(-1, 0, 1), Triple(0, -1, 1), Triple(0, 0, 3), Triple(0, 1, 1))) {
            val timing = SpeechTimingCollector("正文")
            timing.begin(24000)
            timing.range(invalid.first, invalid.second, invalid.third)
            assertTrue(timing.finish().isEmpty())
        }
        val reversed = SpeechTimingCollector("正文")
        reversed.begin(24000)
        reversed.range(10, 0, 1)
        reversed.range(0, 1, 2)
        assertTrue(reversed.finish().isEmpty())
    }

    @Test fun surrogateOffsetsAndUnboundedCallbacksAreRejected() {
        val timing = SpeechTimingCollector("𠀀甲")
        timing.begin(24000)
        timing.range(0, 1, 2)
        assertTrue(timing.finish().isEmpty())
        val flooded = SpeechTimingCollector("甲")
        flooded.begin(24000)
        repeat(1000) { flooded.range(it, 0, 1) }
        assertTrue(flooded.finish().isEmpty())
    }
}
