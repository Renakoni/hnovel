package indi.renakoni.nextvol.tts

/** Real engine boundaries in the synthesized file's timebase, relative to its input text. */
data class SpeechTiming(val timeMs: Long, val start: Int, val end: Int)

/** File synthesis can deliver callbacks ahead of playback. Keep them with their own utterance. */
internal class SpeechTimingCollector(private val text: String) {
    private var sampleRate = 0
    private val frames = mutableListOf<Triple<Int, Int, Int>>()

    @Synchronized fun begin(rate: Int) { sampleRate = rate }
    @Synchronized fun range(frame: Int, start: Int, end: Int) {
        // A buggy engine must not create unbounded metadata for one bounded input.
        if (frames.size <= text.length) frames += Triple(frame, start, end)
    }

    @Synchronized fun finish(): List<SpeechTiming> {
        if (sampleRate !in 8000..192000 || frames.size > text.length) return emptyList()
        if (frames.any { (frame, start, end) -> frame < 0 || start < 0 || end <= start || end > text.length ||
                start > 0 && text[start].isLowSurrogate() || end < text.length && text[end].isLowSurrogate() } ||
            frames.zipWithNext().any { (a, b) -> b.first < a.first || b.second < a.second ||
                b.first == a.first && b.second != a.second }) return emptyList()
        return frames.map { (frame, start, end) -> SpeechTiming(frame * 1000L / sampleRate, start, end) }
    }
}

internal fun List<SpeechTiming>.rangeAt(timeMs: Long): SpeechTiming? = lastOrNull { it.timeMs <= timeMs }
