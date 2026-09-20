package indi.renakoni.nextvol.tts

import java.text.BreakIterator
import java.util.Locale

/** Approximate viewport position within one clip, never a word timestamp or a durable bookmark. */
internal class SpeechFollowEstimate(text: String) {
    private val offsets = buildList {
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var offset = characters.first()
        while (offset != BreakIterator.DONE && offset < text.length) {
            add(offset)
            offset = characters.next()
        }
    }
    private var previousPosition = -1L
    private var previousAnchor = 0

    fun anchorAt(positionMs: Long, durationMs: Long): Int? {
        if (positionMs < 0 || durationMs <= 0 || offsets.isEmpty()) return null
        val fraction = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        val anchor = offsets[(fraction * offsets.size).toInt().coerceAtMost(offsets.lastIndex)]
        // A refined duration must not pull the page backward. An actual backward seek can.
        previousAnchor = if (positionMs >= previousPosition) maxOf(previousAnchor, anchor) else anchor
        previousPosition = positionMs
        return previousAnchor
    }
}
