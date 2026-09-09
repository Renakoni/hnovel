package indi.dmzz_yyhyy.lightnovelreader.data.book

import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BookIdentityTest {
    private val sourceA = Identifier("site", "a")
    private val sourceB = Identifier("site", "b")

    @Test
    fun sameRemoteBookAndChapterIdsRemainDistinctAcrossSourcesAndBooks() {
        val a = SourceBookId(sourceA, "123")
        val b = SourceBookId(sourceB, "123")
        val otherBook = SourceBookId(sourceA, "456")
        assertNotEquals(a.storageKey, b.storageKey)
        assertNotEquals(SourceChapterId(a, "9").storageKey, SourceChapterId(b, "9").storageKey)
        assertNotEquals(SourceChapterId(a, "9").storageKey, SourceChapterId(otherBook, "9").storageKey)
    }

    @Test
    fun lengthPrefixHandlesSeparatorsAndRoundTripsChapterRemoteId() {
        val book = SourceBookId(Identifier("site:with", "id:with"), "book:42")
        val chapter = SourceChapterId(book, "chapter:7")
        val value = StorageKey.decode(book.sourceId, chapter.storageKey)!!
        assertEquals(book.remoteId to chapter.remoteId, StorageKey.decodePair(value))
        assertEquals(null, StorageKey.decodePair(value.dropLast(1)))
    }

    @Test
    fun bookIdentityIsSerializableByValueAndRejectsEmptyRemoteIds() {
        assertEquals(SourceBookId(sourceA, "123"), SourceBookId(sourceA, "123"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SourceBookId(sourceA, "") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SourceChapterId(SourceBookId(sourceA, "123"), "") }
    }
}
