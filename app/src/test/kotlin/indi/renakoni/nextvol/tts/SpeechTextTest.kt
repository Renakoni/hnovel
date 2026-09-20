package indi.renakoni.nextvol.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun shortParagraphsStayWholeAndRequestsDoNotSplitEverySentence() {
        val text = "他说：你好！然后转身。\r\n  Price 3.14 is correct. Next sentence.\n第三段。"
        assertEquals(listOf("他说：你好！然后转身。", "Price 3.14 is correct. Next sentence.", "第三段。"),
            speechSegments(text).map { it.text })
        assertTrue(speechSentences(text).none { it.text.contains('\n') || it.text.endsWith("3.") })
    }

    @Test fun sentenceRangesRetainClosingQuotesAndExactSourceOffsets() {
        val text = "  “你好！”\n他说：再见。\u2028下一句。"
        val sentences = speechSentences(text)
        assertEquals("“你好！”", sentences.first().text)
        assertTrue(sentences.all { it.text == text.substring(it.start, it.end) })
        assertEquals(text.filterNot(Char::isWhitespace), sentences.joinToString("") { it.text }.filterNot(Char::isWhitespace))
    }

    @Test fun mixedTextPreservesOffsetsAndAllSpokenCharacters() {
        val text = "  第一段。Price 3.14 is correct!\n第二段：你好，世界！\n" + "没有标点的长句".repeat(80)
        val segments = speechSegments(text)
        assertTrue(segments.size > 3)
        assertTrue(segments.all { it.text == text.substring(it.start, it.end) && it.text.length <= 200 })
        assertTrue(segments.first().text.length <= 80)
        assertEquals(text.filterNot(Char::isWhitespace), segments.joinToString("") { it.text }.filterNot(Char::isWhitespace))
        assertTrue(segments.zipWithNext().all { (left, right) -> left.end <= right.start })
    }

    @Test fun surrogatePairsAtInputLimitStayTogether() {
        val text = "a".repeat(79) + "𠀀" + "b".repeat(199) + "𠀁" + "tail"
        val segments = speechSegments(text)
        assertEquals(text, segments.joinToString("") { it.text })
        assertTrue(segments.none { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() })
        assertEquals(listOf("𠀀", "𠀁"), speechSegments("𠀀𠀁", 2, 2).map { it.text })
    }

    @Test fun punctuationOnlyAndBlankContentProduceNoSpeech() {
        assertTrue(speechSegments(" \n！？……　\n").isEmpty())
        assertTrue(speechSegments("").isEmpty())
    }

    @Test fun hardLimitsDoNotSplitADecimalAtItsDot() {
        val segments = speechSegments("Price 3.14 dollars and more words", 10, 10)
        assertEquals("Price", segments.first().text)
        assertEquals("3.14", segments[1].text)
    }

    @Test fun contentChangeInvalidatesFingerprint() {
        assertNotEquals(speechDigest("text"), speechDigest("text\n"))
        assertEquals(speechDigest("text"), speechDigest("text"))
    }
}
