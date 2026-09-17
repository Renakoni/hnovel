package indi.renakoni.nextvol.data.localbook

import com.google.re2j.Pattern
import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Line-based recognition; a match marks a boundary and never discards body text. */
object TxtBookParser {
    private const val NUMBERS = "0-9０-９〇零一二两兩三四五六七八九十百千万萬壹贰貳叁參肆伍陆陸柒捌玖拾佰仟"
    const val MAX_TITLE_LENGTH = 120
    const val MAX_CHAPTER_CHARACTERS = 64_000
    const val MAX_CHAPTER_BLOCKS = 1_000
    const val MAX_CHAPTERS = 10_000
    val DEFAULT_RULE = "^(?:(?:正文\\s*)?第\\s*[$NUMBERS]{1,16}\\s*[章回节節话話卷部篇册冊集].*|" +
        "(?:序章|序幕|楔子|引子|前言|尾声|尾聲|后记|後記|番外|幕间|幕間|间章|間章|终章|終章)(?:\\s.*|[:：—-].*)?|" +
        "(?i:(?:chapter|chap\\.?|part|volume)\\s+[0-9ivxlcdm]+(?:\\s.*|[.:：—-].*)?|" +
        "(?:prologue|epilogue)(?:\\s.*|[:：].*)?))$"
    private val numbered = Pattern.compile("^(?:正文\\s*)?第\\s*[$NUMBERS]{1,16}\\s*([章回节節话話卷部篇册冊集])(.*)$")
    private val volume = Pattern.compile("^(?:(?:正文\\s*)?第\\s*[$NUMBERS]{1,16}\\s*[卷部篇册冊集].*|(?i:(?:part|volume)\\s+[0-9ivxlcdm]+.*))$")
    val encodings = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "Big5")

    data class Decoded(val text: String, val encoding: String)

    fun decode(bytes: ByteArray, encoding: String? = null): Decoded {
        require(bytes.isNotEmpty()) { "The text file is empty." }
        fun begins(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 255 == prefix[it] }
        val bom = when {
            begins(0xEF, 0xBB, 0xBF) -> "UTF-8" to 3
            begins(0xFF, 0xFE) -> "UTF-16LE" to 2
            begins(0xFE, 0xFF) -> "UTF-16BE" to 2
            else -> null
        }
        fun strict(name: String): Decoded {
            val charset = Charset.forName(name)
            val offset = if (bom?.first == charset.name()) bom.second else 0
            val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
            require(text.none { it == '\u0000' } && text.count { it.isISOControl() && it !in "\r\n\t" } == 0) {
                "The file is not readable text in this encoding. Choose another encoding."
            }
            return Decoded(text.replace("\r\n", "\n").replace('\r', '\n'), charset.name())
        }
        if (encoding != null) {
            require(encoding in encodings) { "Unsupported text encoding." }
            return strict(encoding)
        }
        if (bom != null) return strict(bom.first)
        try { return strict("UTF-8") } catch (_: CharacterCodingException) { }
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, minOf(bytes.size, 512 * 1024))
        detector.dataEnd()
        val detected = when (detector.detectedCharset?.uppercase()) {
            "GB2312", "GBK", "GB18030" -> "GB18030"
            "BIG5" -> "Big5"
            "UTF-16LE" -> "UTF-16LE"
            "UTF-16BE" -> "UTF-16BE"
            else -> "GB18030"
        }
        return strict(detected)
    }

    fun parse(bytes: ByteArray, title: String, encoding: String? = null, rule: String = DEFAULT_RULE): ParsedLocalBook {
        require(rule.length <= 1_024) { "The chapter rule is too long." }
        val matcher = rule.takeIf { it.isNotBlank() }?.let(Pattern::compile)
        val decoded = decode(bytes, encoding)
        val chapters = mutableListOf<LocalBookChapter>()
        var currentVolume = ""
        var currentTitle = title
        var explicitChapter = false
        var part = 1
        var length = 0
        val blocks = mutableListOf<LocalBookBlock>()
        fun flush() {
            if (blocks.isEmpty() && !explicitChapter) return
            require(chapters.size < MAX_CHAPTERS) { "Too many chapters. Adjust the chapter rule." }
            chapters += LocalBookChapter(if (part == 1) currentTitle else "$currentTitle ($part)", currentVolume, blocks.toList())
            blocks.clear()
            length = 0
            explicitChapter = false
        }
        for (line in decoded.text.lineSequence()) {
            val heading = line.trim()
            val matched = heading.isNotEmpty() && heading.length <= MAX_TITLE_LENGTH &&
                matcher?.matcher(heading)?.matches() == true && (rule != DEFAULT_RULE || !ordinarySentence(heading))
            if (matched) {
                flush()
                part = 1
                if (volume.matcher(heading).matches()) {
                    currentVolume = heading
                    currentTitle = heading
                } else {
                    currentTitle = heading
                    explicitChapter = true
                }
            } else if (line.isNotBlank()) {
                var offset = 0
                while (offset < line.length) {
                    if (length == MAX_CHAPTER_CHARACTERS || blocks.size == MAX_CHAPTER_BLOCKS) { flush(); part++ }
                    var end = minOf(line.length, offset + MAX_CHAPTER_CHARACTERS - length)
                    if (end < line.length && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
                    if (end == offset) { flush(); part++; continue }
                    blocks += LocalBookBlock.Text(line.substring(offset, end))
                    length += end - offset
                    offset = end
                }
            }
        }
        flush()
        require(chapters.any { it.blocks.isNotEmpty() }) { "The file has no readable text." }
        return ParsedLocalBook(title, chapters, encoding = decoded.encoding)
    }

    private fun ordinarySentence(line: String): Boolean {
        val match = numbered.matcher(line)
        if (!match.matches()) return false
        val suffix = match.group(2)
        return when (match.group(1)) {
            "节", "節" -> suffix.startsWith("课") || suffix.startsWith("課")
            "回" -> suffix.startsWith("来") || suffix.startsWith("來") || suffix.startsWith("事") || suffix.startsWith("合")
            "部" -> suffix.startsWith("分")
            "集" -> suffix.startsWith("合") || suffix.startsWith("和")
            else -> false
        }
    }
}
