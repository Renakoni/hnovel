package indi.renakoni.nextvol.ui.book.reader

import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorySearchTest {
    @Test fun matchesTitleFragmentsAndNumberTextIgnoringCaseAndOuterWhitespace() = runBlocking {
        val book = book("Chapter 12 · The Northern Wind", "第123章 春日", "Interlude")
        assertEquals(listOf("id-0"), searchDirectory(book, " northern WIND ").map { it.chapter.id })
        assertEquals(listOf("id-0", "id-1"), searchDirectory(book, "12").map { it.chapter.id })
        assertEquals(listOf("id-1"), searchDirectory(book, "春日").map { it.chapter.id })
        assertTrue(searchDirectory(book, "  \n\t").isEmpty())
        assertTrue(searchDirectory(book, "missing").isEmpty())
    }

    @Test fun txtEpubAndOnlineIdsRemainOpaqueAndSameTitlesKeepTheirVolume() = runBlocking {
        val ids = listOf("local:txt:book-a:chapter-12", "OPS/section_0012.xhtml#chapter-12", "https://example.org/book/12.html?source=a")
        val book = BookVolumes("book", ids.mapIndexed { index, id ->
            Volume("volume:$index", "Volume $index", listOf(ChapterInformation(id, "Interlude")))
        })
        val found = searchDirectory(book, "Interlude")
        assertEquals(ids, found.map { it.chapter.id })
        assertEquals(listOf("Volume 0", "Volume 1", "Volume 2"), found.map { it.volumeTitle })
        assertEquals(3, found.map { it.key }.distinct().size)
        found.forEachIndexed { index, item -> assertSame(book.volumes[index].chapters.single(), item.chapter) }
    }

    @Test fun tenThousandMatchesPreserveDirectoryOrderAndRefreshUsesTheNewTitles() = runBlocking {
        val book = book(*(1..10_000).map { "Chapter $it" }.toTypedArray())
        val all = searchDirectory(book, "Chapter")
        assertEquals(10_000, all.size)
        assertEquals("Chapter 1", all.first().chapter.title)
        assertEquals("Chapter 10000", all.last().chapter.title)
        val updated = book.copy(volumes = listOf(book.volumes.single().copy(chapters = listOf(ChapterInformation("id-0", "Renamed")))))
        assertTrue(searchDirectory(updated, "Chapter").isEmpty())
        assertEquals("id-0", searchDirectory(updated, "renamed").single().chapter.id)
    }

    private fun book(vararg titles: String) = BookVolumes("book", listOf(Volume("volume", "Volume", titles.mapIndexed { index, title ->
        ChapterInformation("id-$index", title)
    })))
}
