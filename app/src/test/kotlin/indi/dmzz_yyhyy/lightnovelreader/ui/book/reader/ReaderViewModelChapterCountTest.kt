package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import indi.dmzz_yyhyy.lightnovelreader.data.book.ChapterSource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderViewModelChapterCountTest {
    @Test
    fun chapterCountUsesTheRefreshedEmissionAfterCachedData() = runTest {
        val source = mockk<ChapterSource>()
        val cached: Result<BookVolumes, WebRequestError> = Ok(
            BookVolumes("book", listOf(Volume("volume", "cached", emptyList()))),
        )
        val refreshed: Result<BookVolumes, WebRequestError> = Ok(
            BookVolumes(
                "book",
                listOf(
                    Volume(
                        "volume",
                        "remote",
                        listOf(ChapterInformation("one", "One"), ChapterInformation("two", "Two")),
                    ),
                ),
            ),
        )
        every { source.getBookVolumesFlow("book") } returns flowOf(cached, refreshed)

        assertEquals(2, latestChapterCount(source, "book"))
    }
}
