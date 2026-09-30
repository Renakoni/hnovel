package indi.renakoni.nextvol.defaultplugin.wenku8

import android.app.Application
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.defaultplugin.wenku8.book.Wenku8WebsiteDataSource
import indi.renakoni.nextvol.ui.book.detail.directoryChapters
import indi.renakoni.nextvol.ui.book.detail.directoryPageChapters
import indi.renakoni.nextvol.ui.book.detail.directoryPageCount
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class Wenku8DirectoryFormatTest {
    private fun catalog(counts: List<Int>): BookVolumes = runBlocking {
        val names = listOf("第一卷", "第二卷", "第三卷", "第四卷")
        var chapterId = 1000
        val html = buildString {
            append("<html><body><table><tbody>")
            counts.forEachIndexed { index, count ->
                append("<tr><td class='vcss' vid='v${index + 1}'>${names[index]}</td></tr>")
                val titles = listOf("序章") + (1..count - 4).map { "第${it}章" } + listOf("插图", "特典", "后记")
                titles.forEach { title ->
                    append("<tr><td><a href='${++chapterId}.htm'>$title</a></td></tr>")
                }
            }
            append("</tbody></table></body></html>")
        }
        val api = mockk<Wenku8Api>()
        val url = "https://wenku8.test/novel/0/123/index.htm"
        coEvery { api.getWithWenku8Cookie(url) } returns Ok(Jsoup.parse(html))
        val result = Wenku8WebsiteDataSource("https://wenku8.test", api).getBookVolumes("123").get()!!
        coVerify(exactly = 1) { api.getWithWenku8Cookie(url) }
        result
    }

    @Test fun aSingleExplicitVolumeKeepsItsNameAndCountsEveryDirectoryEntry() {
        val volume = catalog(listOf(13)).volumes.single()
        assertEquals("第一卷", volume.volumeTitle)
        assertEquals(13, volume.chapters.size)
        assertEquals(listOf("插图", "特典", "后记"), volume.chapters.takeLast(3).map { it.title })
        assertEquals(1, directoryPageCount(volume.chapters.size))
    }

    @Test fun theFourVolumeLayoutUsesGlobalCountsWithoutRenamingRepeatedChapterTitles() {
        val book = catalog(listOf(13, 14, 13, 15))
        val entries = directoryChapters(book.volumes)
        assertEquals(listOf(13, 14, 13, 15), book.volumes.map { it.chapters.size })
        assertEquals(55, entries.size)
        assertEquals(1, directoryPageCount(entries.size))
        assertEquals(4, entries.count { it.chapter.title == "序章" })
        val descending = directoryPageChapters(entries, 0, true)
        assertEquals(listOf("v4", "v3", "v2", "v1"), descending.map { it.volumeId }.distinct())
        assertEquals("后记", descending.first().chapter.title)
        assertEquals("序章", entries.first().chapter.title)
        assertEquals(entries.map { it.chapter.id }.reversed(), descending.map { it.chapter.id })
    }

    @Test fun reversingBeforePagingAcrossTheNinetyEightChapterBoundaryPreservesVolumeIdentity() {
        val book = catalog(listOf(98, 105, 4))
        val entries = directoryChapters(book.volumes)
        val firstPage = directoryPageChapters(entries, 0, false)
        assertEquals(100, firstPage.size)
        assertEquals(listOf("v2", "v2"), firstPage.takeLast(2).map { it.volumeId })
        val descendingPages = (0 until directoryPageCount(entries.size)).map { directoryPageChapters(entries, it, true) }
        assertEquals(listOf(100, 100, 7), descendingPages.map { it.size })
        val firstDescendingPage = descendingPages.first()
        assertEquals(listOf("v3", "v3", "v3", "v3", "v2", "v2", "v2"), firstDescendingPage.take(7).map { it.volumeId })
        assertEquals(listOf("后记", "特典", "插图", "序章", "后记", "特典", "插图"), firstDescendingPage.take(7).map { it.chapter.title })
        assertEquals(entries.takeLast(100).map { it.key }.reversed(), firstDescendingPage.map { it.key })
        assertSame(book.volumes.last().chapters.last(), firstDescendingPage.first().chapter)
        assertEquals(List(7) { "v1" }, descendingPages.last().map { it.volumeId })
        assertEquals(entries.take(7).map { it.key }.reversed(), descendingPages.last().map { it.key })
        assertEquals(entries.map { it.key }.reversed(), descendingPages.flatten().map { it.key })
    }
}
