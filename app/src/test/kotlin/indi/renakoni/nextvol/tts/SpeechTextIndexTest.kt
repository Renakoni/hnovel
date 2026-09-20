package indi.renakoni.nextvol.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechTextIndexTest {
    @Test fun repeatedTextMapsToItsOriginalComponentRatherThanTheFirstMatch() {
        val index = SpeechTextIndex(listOf(0 to "重复句。", 2 to "重复句。"))
        assertEquals("重复句。\n重复句。", index.text)
        assertEquals(listOf(SpeechTextIndex.Range(2, 0, 4)),
            index.ranges(SpeechPosition("book", "chapter", index.fingerprint, 5, 9)))
    }

    @Test fun spanningComponentsPreservesWhitespaceAndUtf16Offsets() {
        val index = SpeechTextIndex(listOf(0 to " 甲𠀀\r\n", 3 to "", 4 to "乙\u2028丙。"))
        val position = SpeechPosition("book", "chapter", index.fingerprint, 2, index.text.length - 1)
        val ranges = index.ranges(position)
        assertEquals(listOf(SpeechTextIndex.Range(0, 2, 6), SpeechTextIndex.Range(4, 0, 3)), ranges)
        assertEquals(SpeechChapter("book", "chapter", "", "", index.text).fingerprint, index.fingerprint)
    }

    @Test fun staleOrInvalidRangesAreNotGuessedOrClampedToDifferentText() {
        val index = SpeechTextIndex(listOf(0 to "Actual text."))
        val valid = SpeechPosition("book", "chapter", index.fingerprint, 0, 6)
        assertTrue(index.ranges(valid.copy(fingerprint = "old-text")).isEmpty())
        assertTrue(index.ranges(valid.copy(start = -1)).isEmpty())
        assertTrue(index.ranges(valid.copy(end = 100)).isEmpty())
        assertTrue(index.ranges(valid.copy(end = 0)).isEmpty())
    }
}
