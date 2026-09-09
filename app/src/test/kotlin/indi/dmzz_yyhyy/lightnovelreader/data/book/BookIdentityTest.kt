package indi.dmzz_yyhyy.lightnovelreader.data.book

import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

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
    fun structuredKeysHandleSeparatorsAndRoundTripCompleteIdentities() {
        val book = SourceBookId(Identifier("site:with", "id:with"), "https://example.invalid/book?a=1,b=2#分卷")
        val chapter = SourceChapterId(book, "chapter:7/../,章")
        assertEquals(book, SourceBookId.fromStorageKey(book.storageKey))
        assertEquals(chapter, SourceChapterId.fromStorageKey(chapter.storageKey))
        assertEquals(book, Json.decodeFromString<SourceBookId>(Json.encodeToString(book)))
        assertEquals(chapter, Json.decodeFromString<SourceChapterId>(Json.encodeToString(chapter)))
        assertEquals("volume,1", BookIdentity.volumeRemoteId(BookIdentity.volumeKey(book, "volume,1"), book))
        org.junit.Assert.assertFalse(book.storageKey.contains(','))
        org.junit.Assert.assertFalse(book.storageKey.contains('/'))
        assertEquals(64, book.fileKey.length)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SourceChapterId.fromStorageKey(chapter.storageKey.dropLast(1))
        }
    }

    @Test
    fun bookIdentityIsSerializableByValueAndRejectsEmptyRemoteIds() {
        assertEquals(SourceBookId(sourceA, "123"), SourceBookId(sourceA, "123"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SourceBookId(sourceA, "") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SourceChapterId(SourceBookId(sourceA, "123"), "") }
    }
}
