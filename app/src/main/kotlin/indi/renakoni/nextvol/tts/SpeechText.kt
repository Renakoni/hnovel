package indi.renakoni.nextvol.tts

import java.security.MessageDigest

/** Offsets refer to the processed chapter text, before any speech-only transformations. */
data class SpeechSegment(val start: Int, val end: Int, val text: String)

internal fun speechDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Bounded UTF-16 input, preserving sentence boundaries and never splitting a surrogate pair. */
fun speechSegments(text: String, maxLength: Int = 200, firstLength: Int = 80): List<SpeechSegment> {
    require(maxLength >= 2 && firstLength >= 2)
    val result = mutableListOf<SpeechSegment>()
    var start = 0
    while (start < text.length) {
        while (start < text.length && text[start].isWhitespace()) start++
        if (start == text.length) break
        val limit = if (result.isEmpty()) minOf(firstLength, maxLength) else maxLength
        var end = minOf(text.length, start + limit)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        if (end < text.length) {
            val sentence = (start until end).lastOrNull { index ->
                text[index] in "。！？!?；;\n" || text[index] == '.' &&
                    !(index > start && text[index - 1].isDigit() && text.getOrNull(index + 1)?.isDigit() == true)
            }
            val whitespace = (start until end).lastOrNull { text[it].isWhitespace() }
            end = sentence?.plus(1) ?: whitespace?.takeIf { it > start } ?: end
        }
        var spokenEnd = end
        while (spokenEnd > start && text[spokenEnd - 1].isWhitespace()) spokenEnd--
        val spoken = text.substring(start, spokenEnd)
        if (spoken.codePoints().anyMatch(Character::isLetterOrDigit)) {
            result += SpeechSegment(start, spokenEnd, spoken)
        }
        start = end
    }
    return result
}
