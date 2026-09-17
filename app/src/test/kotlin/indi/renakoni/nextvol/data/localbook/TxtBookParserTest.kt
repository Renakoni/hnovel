package indi.renakoni.nextvol.data.localbook

import com.google.re2j.PatternSyntaxException
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

class TxtBookParserTest {
    private fun parse(text: String, rule: String = TxtBookParser.DEFAULT_RULE) =
        TxtBookParser.parse(text.toByteArray(), "Example", rule = rule)

    private fun body(book: ParsedLocalBook) = book.chapters.flatMap { it.blocks }
        .filterIsInstance<LocalBookBlock.Text>().joinToString("\n") { it.value }

    @Test fun shortAdjacentChaptersDoNotRequireWhitespaceAfterTheirNumber() {
        val book = parse("第一章开端\n甲\n第２章旅程\n乙\n第叁章 终点\n丙")
        assertEquals(listOf("第一章开端", "第２章旅程", "第叁章 终点"), book.chapters.map { it.title })
        assertEquals("甲\n乙\n丙", body(book))
    }

    @Test fun volumeImmediatelyBeforeItsFirstChapterKeepsBothBoundaries() {
        val book = parse("第一卷 起点\n第一章开端\n甲\n第二章\n乙\n第二卷 归途\n第一章\n丙")
        assertEquals(listOf("第一章开端", "第二章", "第一章"), book.chapters.map { it.title })
        assertEquals(listOf("第一卷 起点", "第一卷 起点", "第二卷 归途"), book.chapters.map { it.volume })
        assertEquals("甲\n乙\n丙", body(book))
    }

    @Test fun prefaceAndSpecialChaptersAreKeptAsReadingContent() {
        val book = parse("开篇说明\n第一章\n正文\n番外 旅途\n番外正文\n后记 作者的话\n后记正文")
        assertEquals(listOf("Example", "第一章", "番外 旅途", "后记 作者的话"), book.chapters.map { it.title })
        assertEquals("开篇说明\n正文\n番外正文\n后记正文", body(book))
    }

    @Test fun ordinaryLessonAndReturnSentencesStayInTheBody() {
        val book = parse("第一章 开始\n第一节课开始了。\n第一回来的人站在那里。\n第一部分已经完成。\n第二章 结束\n结尾")
        assertEquals(2, book.chapters.size)
        assertTrue(body(book).contains("第一节课开始了。"))
        assertTrue(body(book).contains("第一回来的人站在那里。"))
        assertTrue(body(book).contains("第一部分已经完成。"))
    }

    @Test fun englishRomanNumeralsAndEpilogueAreRecognized() {
        val book = parse("Chapter I The beginning\nFirst.\nCHAPTER II: The road\nSecond.\nEpilogue\nLast.")
        assertEquals(3, book.chapters.size)
        assertEquals("First.\nSecond.\nLast.", body(book))
    }

    @Test fun unmatchedTextIsStillACompleteBook() {
        val book = parse("普通正文\n下一段\n最后一段")
        assertEquals(listOf("Example"), book.chapters.map { it.title })
        assertEquals("普通正文\n下一段\n最后一段", body(book))
    }

    @Test fun aCustomLineRuleChangesThePreviewWithoutRemovingText() {
        val book = parse("导言\n1. 开始\n正文甲\n2. 结束\n正文乙", "^[0-9]+[.] .+$")
        assertEquals(listOf("Example", "1. 开始", "2. 结束"), book.chapters.map { it.title })
        assertEquals("导言\n正文甲\n正文乙", body(book))
        assertEquals("第一章\n正文", body(parse("第一章\n正文", "")))
    }

    @Test fun unsupportedAndInvalidRulesFailBeforeImport() {
        for (rule in listOf("(", "(?<=chapter) [0-9]+", "(a)\\1")) {
            assertThrows(PatternSyntaxException::class.java) { parse("正文", rule) }
        }
    }

    @Test fun longLinesAreBoundedWithoutCuttingASupplementaryCharacter() {
        val text = "甲".repeat(TxtBookParser.MAX_CHAPTER_CHARACTERS - 1) + "😀" + "乙".repeat(20)
        val book = parse(text, "")
        assertEquals(2, book.chapters.size)
        val blocks = book.chapters.flatMap { it.blocks }.filterIsInstance<LocalBookBlock.Text>()
        assertEquals(text, blocks.joinToString("") { it.value })
        assertTrue(blocks.all { it.value.length <= TxtBookParser.MAX_CHAPTER_CHARACTERS })
        assertTrue(blocks.all { it.value == it.value.toByteArray().toString(Charsets.UTF_8) })
    }

    @Test fun utf8AndUtf16ByteOrderMarksAreHandledBeforeOtherEncodings() {
        val text = "第一章 开始\r\n完整正文"
        val cases = listOf(
            "UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
        )
        for ((encoding, prefix) in cases) {
            val decoded = TxtBookParser.decode(prefix + text.toByteArray(Charset.forName(encoding)))
            assertEquals(encoding, decoded.encoding)
            assertEquals(text.replace("\r\n", "\n"), decoded.text)
        }
        assertEquals(text.replace("\r\n", "\n"), TxtBookParser.decode(text.toByteArray()).text)
    }

    @Test fun gb18030AndBig5CanBeDetectedAndExplicitlySelected() {
        val cases = listOf(
            "GB18030" to "这是一本中文小说，第一章介绍人物，第二章开始旅程。读者可以阅读完整正文。\n".repeat(40),
            "Big5" to "這是一本繁體中文小說，第一章介紹人物，第二章開始旅程。讀者可以閱讀完整正文。\n".repeat(40),
        )
        for ((encoding, text) in cases) {
            val bytes = text.toByteArray(Charset.forName(encoding))
            assertEquals(text, TxtBookParser.decode(bytes, encoding).text)
            assertEquals(text, TxtBookParser.decode(bytes).text)
        }
    }

    @Test fun explicitUtf16WithoutBomWorksAndMalformedInputIsNotReplaced() {
        val text = "完整正文"
        assertEquals(text, TxtBookParser.decode(text.toByteArray(Charsets.UTF_16LE), "UTF-16LE").text)
        assertThrows(CharacterCodingException::class.java) {
            TxtBookParser.decode(byteArrayOf(0xE4.toByte(), 0xB8.toByte()), "UTF-8")
        }
        assertThrows(IllegalArgumentException::class.java) { parse("\u0000binary") }
        assertThrows(IllegalArgumentException::class.java) { parse("\n\t ") }
    }
}
