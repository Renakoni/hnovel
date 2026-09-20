package indi.renakoni.nextvol.tts

/** The same newline join used by speechText; image components do not consume spoken offsets. */
internal class SpeechTextIndex(parts: List<Pair<Int, String>>) {
    data class Range(val componentIndex: Int, val start: Int, val end: Int)
    private val components: List<Range>
    val text: String
    val fingerprint: String

    init {
        val joined = StringBuilder()
        components = parts.mapIndexed { index, (component, value) ->
            if (index > 0) joined.append('\n')
            val start = joined.length
            joined.append(value)
            Range(component, start, joined.length)
        }
        text = joined.toString()
        fingerprint = speechDigest("speech-text-v1\u0000$text")
    }

    fun ranges(position: SpeechPosition): List<Range> {
        if (position.fingerprint != fingerprint || position.start < 0 ||
            position.end > text.length || position.start >= position.end ||
            position.anchor !in position.start until position.end) return emptyList()
        return components.mapNotNull { component ->
            val start = maxOf(position.start, component.start)
            val end = minOf(position.end, component.end)
            if (start >= end) null else Range(component.componentIndex, start - component.start, end - component.start)
        }
    }
}
