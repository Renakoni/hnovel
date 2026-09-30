package indi.renakoni.nextvol.ui.book.detail

import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.Assert.*
import org.junit.Test

class DetailDirectoryPagingTest {
    private fun volume(count: Int) = Volume(
        "volume", "Volume", (1..count).map { ChapterInformation("id-$it", "Chapter $it") },
    )

    @Test fun shortAndEmptyBooksKeepOnePage() {
        for (count in listOf(0, 1, 99, 100)) assertEquals(1, directoryPageCount(count))
        assertTrue(directoryPageChapters(directoryChapters(listOf(volume(0))), 0, false).isEmpty())
        assertTrue(directoryPageChapters(directoryChapters(listOf(volume(0))), 0, true).isEmpty())
        assertEquals(12, directoryPageChapters(directoryChapters(listOf(volume(12))), 0, false).size)
    }

    @Test fun pageBoundariesHaveNoMissingOrRepeatedChapters() {
        val volume = volume(1001)
        val entries = directoryChapters(listOf(volume))
        val chapters = (0 until directoryPageCount(1001)).flatMap { directoryPageChapters(entries, it, false) }.map { it.chapter }
        assertEquals(volume.chapters, chapters)
        assertEquals(1000..1000, directoryPageRange(1001, 10))
    }

    @Test fun descendingFillsPagesFromTheEndWithoutChangingCanonicalOrder() {
        val volume = volume(205)
        val original = volume.chapters.toList()
        val entries = directoryChapters(listOf(volume))
        assertEquals((205 downTo 106).map { "id-$it" },
            directoryPageChapters(entries, 0, true).map { it.chapter.id })
        assertEquals((105 downTo 6).map { "id-$it" },
            directoryPageChapters(entries, 1, true).map { it.chapter.id })
        assertEquals((5 downTo 1).map { "id-$it" },
            directoryPageChapters(entries, 2, true).map { it.chapter.id })
        assertEquals(original, volume.chapters)
        assertSame(volume.chapters[204], directoryPageChapters(entries, 0, true).first().chapter)
    }

    @Test fun descendingOneHundredAndOneChaptersLeavesOnlyChapterOneOnTheLastPage() {
        val entries = directoryChapters(listOf(volume(101)))
        assertEquals(100 downTo 1, directoryPageRange(101, 0, true))
        assertEquals(0 downTo 0, directoryPageRange(101, 1, true))
        assertEquals((101 downTo 2).map { "id-$it" },
            directoryPageChapters(entries, 0, true).map { it.chapter.id })
        assertEquals("id-1", directoryPageChapters(entries, 1, true).single().chapter.id)
    }

    @Test fun chapterLocationUsesTheCurrentOrderAndMatchesPageBoundaries() {
        for (count in listOf(1, 13, 100, 101, 200, 207, 1001)) {
            for (descending in listOf(false, true)) {
                for (index in 0 until count) {
                    val page = directoryPageForChapter(count, index, descending)
                    assertTrue(index in directoryPageRange(count, page, descending))
                }
            }
        }
        assertEquals(0, directoryPageForChapter(101, 100, true))
        assertEquals(0, directoryPageForChapter(101, 1, true))
        assertEquals(1, directoryPageForChapter(101, 0, true))
        assertEquals(0, directoryPageForChapter(0, -1, true))
        assertEquals(0, directoryPageForChapter(101, -1, true))
    }

    @Test fun changedCatalogClampsAnOldPageWithoutLosingItsLastChapter() {
        assertEquals("id-101", directoryPageChapters(directoryChapters(listOf(volume(101))), 9, false).single().chapter.id)
        assertEquals(0..99, directoryPageRange(201, -1))
        assertEquals("id-1", directoryPageChapters(directoryChapters(listOf(volume(101))), 9, true).single().chapter.id)
        assertEquals(200 downTo 101, directoryPageRange(201, -1, true))
    }

    @Test fun ninetyEightChapterVolumeFlowsIntoTheSameHundredChapterPage() {
        val first = volume(98)
        val second = Volume("second", "Second volume", (1..105).map { ChapterInformation("second-$it", "Chapter $it") })
        val third = Volume("third", "Third volume", (1..4).map { ChapterInformation("third-$it", "Chapter $it") })
        val entries = directoryChapters(listOf(first, second, third))
        assertEquals(207, entries.size)
        val firstPage = directoryPageChapters(entries, 0, false)
        assertEquals(100, firstPage.size)
        assertSame(first.chapters.last(), firstPage[97].chapter)
        assertSame(second.chapters.first(), firstPage[98].chapter)
        assertEquals("second-2", firstPage.last().chapter.id)
        assertEquals("second-3", directoryPageChapters(entries, 1, false).first().chapter.id)
        assertEquals("second-103", directoryPageChapters(entries, 2, false).first().chapter.id)
        assertEquals("third-4", directoryPageChapters(entries, 2, false).last().chapter.id)
        assertEquals(entries, (0 until 3).flatMap { directoryPageChapters(entries, it, false) })
        val descendingPages = (0 until 3).map { directoryPageChapters(entries, it, true) }
        assertEquals(listOf(100, 100, 7), descendingPages.map { it.size })
        assertEquals("third-4", descendingPages[0].first().chapter.id)
        assertEquals("second-10", descendingPages[0].last().chapter.id)
        assertEquals("second-9", descendingPages[1].first().chapter.id)
        assertEquals("id-8", descendingPages[1].last().chapter.id)
        assertEquals(entries.asReversed(), descendingPages.flatten())
    }

    @Test fun lightNovelHeadingsDoNotCountAsChaptersOrRewriteSourceTitles() {
        val titles = listOf("序章", "一章 来自天王寺美丽的提议", "终章", "番外篇", "后记", "特典", "插图")
        val volumes = (1..3).map { number ->
            Volume("v$number", "第${number}卷", titles.mapIndexed { index, title -> ChapterInformation("v$number-$index", title) })
        }
        val entries = directoryChapters(listOf(volume(0)) + volumes)
        assertEquals(21, entries.size)
        assertEquals(1, directoryPageCount(entries.size))
        assertEquals(volumes.flatMap { it.chapters }, entries.map { it.chapter })
        for ((index, entry) in entries.withIndex()) {
            assertSame(volumes[index / titles.size].chapters[index % titles.size], entry.chapter)
            assertEquals(volumes[index / titles.size].volumeTitle, entry.volumeTitle)
        }
    }
}
