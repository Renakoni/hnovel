package indi.dmzz_yyhyy.lightnovelreader.data.web.proxy

import android.app.Application
import com.github.michaelbull.result.Ok
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ProxyCachedWebBookDataSourceTest {
    @Test
    fun repeatedRequestsUseTheRequestKeyAndKeepDifferentDataTypesIndependent() = runTest {
        val cache = Cache()
        val sourceId = Identifier("fixture", "source")
        val origin = mockk<WebBookDataSource>(relaxed = true)
        every { origin.id } returns sourceId
        every { origin.cache } returns cache
        val remote = mockk<ProxyWebBookDataSource>()
        every { remote.origin } returns origin

        val volumes = BookVolumes("same", emptyList())
        val firstBookVolumes = BookVolumes("book-a", emptyList())
        val secondBookVolumes = BookVolumes("book-b", emptyList())
        val chapter = ChapterContent(
            id = "chapter",
            title = "title",
            content = Json.parseToJsonElement("""{"components":[]}""").jsonObject,
        )
        coEvery { remote.getBookVolumes("same", WebDataSourcePriority.Default) } returns Ok(volumes)
        coEvery { remote.getBookVolumes("book-a", WebDataSourcePriority.Default) } returns Ok(firstBookVolumes)
        coEvery { remote.getBookVolumes("book-b", WebDataSourcePriority.Default) } returns Ok(secondBookVolumes)
        coEvery { remote.getChapterContent("", "same", WebDataSourcePriority.Default) } returns Ok(chapter)

        val cached = ProxyCachedWebBookDataSource(remote)
        assertNotEquals("same".hashCode(), sourceId.hashCode())

        assertEquals(Ok(volumes), cached.getBookVolumes("same", WebDataSourcePriority.Default))
        assertEquals(Ok(volumes), cached.getBookVolumes("same", WebDataSourcePriority.Default))
        assertEquals(Ok(chapter), cached.getChapterContent("", "same", WebDataSourcePriority.Default))
        assertEquals(Ok(chapter), cached.getChapterContent("", "same", WebDataSourcePriority.Default))
        assertEquals(Ok(firstBookVolumes), cached.getBookVolumes("book-a", WebDataSourcePriority.Default))
        assertEquals(Ok(secondBookVolumes), cached.getBookVolumes("book-b", WebDataSourcePriority.Default))
        assertEquals(Ok(firstBookVolumes), cached.getBookVolumes("book-a", WebDataSourcePriority.Default))
        assertEquals(Ok(secondBookVolumes), cached.getBookVolumes("book-b", WebDataSourcePriority.Default))

        coVerify(exactly = 1) { remote.getBookVolumes("same", WebDataSourcePriority.Default) }
        coVerify(exactly = 1) { remote.getBookVolumes("book-a", WebDataSourcePriority.Default) }
        coVerify(exactly = 1) { remote.getBookVolumes("book-b", WebDataSourcePriority.Default) }
        coVerify(exactly = 1) { remote.getChapterContent("", "same", WebDataSourcePriority.Default) }
        assertEquals(volumes, cache.getCache<BookVolumes>("same".hashCode()))
        assertEquals(chapter, cache.getCache<ChapterContent>("same".hashCode()))
    }
}
