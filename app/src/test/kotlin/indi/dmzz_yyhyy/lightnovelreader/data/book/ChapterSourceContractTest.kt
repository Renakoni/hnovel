package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
abstract class ChapterSourceContractTest {
    internal val fixture = BookRepositoryFixture()
    private val book = BookIdentity.book("book")
    private val chapter = SourceChapterId(book, "chapter")
    private val events = mutableListOf<String>()
    private val localChapter = ChapterContent(chapter.storageKey, "local", JsonObject(emptyMap()))
    private val remoteChapter = localChapter.copy(id = "chapter", title = "remote", nextChapter = "next")
    private val localVolumes = BookVolumes(book.storageKey, listOf(Volume(BookIdentity.volumeKey(book, "volume"), "local", emptyList())))
    private val remoteVolumes = BookVolumes("book", listOf(Volume("volume", "remote", emptyList())))
    private val error = WebRequestError("offline", "request failed")

    protected abstract fun source(): ChapterSource

    @Before
    fun setUp() {
        coEvery { fixture.local.getChapterContent(chapter.storageKey) } answers { events += "local"; localChapter }
        coEvery { fixture.local.getBookVolumes(book.storageKey) } answers { events += "local"; localVolumes }
        coEvery { fixture.remote.getChapterContent("chapter", "book", any()) } answers {
            events += "remote"; Ok(remoteChapter)
        }
        coEvery { fixture.remote.getBookVolumes("book", any()) } answers { events += "remote"; Ok(remoteVolumes) }
        coEvery { fixture.local.updateChapterContent(any()) } answers {
            events += "store:${firstArg<ChapterContent>().title}"
        }
        coEvery { fixture.local.updateBookVolumes(any()) } answers {
            events += "store:${firstArg<BookVolumes>().volumes.single().volumeTitle}"
        }
        every { fixture.text.processChapterContent(book.storageKey, any()) } answers {
            val chapter = secondArg<() -> ChapterContent>()()
            events += "process:${chapter.title}"
            chapter.copy(title = "processed:${chapter.title}")
        }
        every { fixture.text.processBookVolumes(any()) } answers {
            val volumes = firstArg<() -> BookVolumes>()()
            val volume = volumes.volumes.single()
            events += "process:${volume.volumeTitle}"
            volumes.copy(volumes = listOf(volume.copy(volumeTitle = "processed:${volume.volumeTitle}")))
        }
    }

    @Test
    fun chapterEmitsProcessedLocalThenRemoteAndStoresRawBeforeRemoteEmission() = runTest {
        val actual = mutableListOf<Result<ChapterContent, WebRequestError>>()
        source().getChapterContentFlow("chapter", "book", WebDataSourcePriority.High).collect {
            actual += it
            events += "emit"
        }
        assertEquals(listOf(Ok(localChapter.copy(title = "processed:local")), Ok(chapter.bind(remoteChapter).copy(title = "processed:remote"))), actual)
        assertEquals(listOf("local", "process:local", "emit", "remote", "store:remote", "process:remote", "emit"), events)
        coVerify(exactly = 1) { fixture.remote.getChapterContent("chapter", "book", WebDataSourcePriority.High) }
        coVerify(exactly = 1) { fixture.local.updateChapterContent(chapter.bind(remoteChapter)) }
    }

    @Test
    fun volumesKeepLocalRemoteProcessingAndPersistenceOrder() = runTest {
        val actual = mutableListOf<Result<BookVolumes, WebRequestError>>()
        source().getBookVolumesFlow("book", WebDataSourcePriority.High).collect {
            actual += it
            events += "emit"
        }
        assertEquals(
            listOf(Ok(BookVolumes(book.storageKey, listOf(Volume(BookIdentity.volumeKey(book, "volume"), "processed:local", emptyList())))),
                Ok(BookVolumes(book.storageKey, listOf(Volume(BookIdentity.volumeKey(book, "volume"), "processed:remote", emptyList()))))),
            actual,
        )
        assertEquals(listOf("local", "process:local", "emit", "remote", "store:remote", "process:remote", "emit"), events)
        coVerify(exactly = 1) { fixture.remote.getBookVolumes("book", WebDataSourcePriority.High) }
        coVerify(exactly = 1) { fixture.local.updateBookVolumes(book.bind(remoteVolumes)) }
    }

    @Test
    fun remoteFailureRetainsProcessedCachedContentForBothFlows() = runTest {
        coEvery { fixture.remote.getChapterContent(any(), any(), any()) } returns Err(error)
        coEvery { fixture.remote.getBookVolumes(any(), any()) } returns Err(error)
        val chapter = source().getChapterContentFlow("chapter", "book").toList()
        val volumes = source().getBookVolumesFlow("book").toList()
        assertEquals(listOf(Ok(localChapter.copy(title = "processed:local"))), chapter)
        assertEquals(
            listOf(Ok(BookVolumes(book.storageKey, listOf(Volume(BookIdentity.volumeKey(book, "volume"), "processed:local", emptyList()))))),
            volumes,
        )
        coVerify(exactly = 1) { fixture.remote.getChapterContent("chapter", "book", WebDataSourcePriority.Default) }
        coVerify(exactly = 1) { fixture.remote.getBookVolumes("book", WebDataSourcePriority.Default) }
        coVerify(exactly = 0) { fixture.local.updateChapterContent(any()) }
        coVerify(exactly = 0) { fixture.local.updateBookVolumes(any()) }
    }

    @Test
    fun missingCacheEmitsOnlyTheRemoteResult() = runTest {
        coEvery { fixture.local.getChapterContent(any()) } returns null
        coEvery { fixture.local.getBookVolumes(any()) } returns null
        assertEquals(listOf(Ok(chapter.bind(remoteChapter).copy(title = "processed:remote"))), source().getChapterContentFlow("chapter", "book").toList())
        assertEquals(1, source().getBookVolumesFlow("book").toList().size)

        coEvery { fixture.remote.getChapterContent(any(), any(), any()) } returns Err(error)
        coEvery { fixture.remote.getBookVolumes(any(), any()) } returns Err(error)
        assertEquals(listOf(Err(error)), source().getChapterContentFlow("chapter", "book").toList())
        assertEquals(listOf(Err(error)), source().getBookVolumesFlow("book").toList())
    }

    @Test
    fun takingOnlyTheCachedValueDoesNotStartRemoteWork() = runTest {
        source().getChapterContentFlow("chapter", "book").first()
        source().getBookVolumesFlow("book").first()
        coVerify(exactly = 0) { fixture.remote.getChapterContent(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.remote.getBookVolumes(any(), any()) }
        coVerify(exactly = 0) { fixture.local.updateChapterContent(any()) }
        coVerify(exactly = 0) { fixture.local.updateBookVolumes(any()) }
    }

    @Test
    fun flowsStayColdAndRecollectionResolvesTheSameSourceAgain() = runTest {
        val source = source()
        val chapters = source.getChapterContentFlow("chapter", "book")
        val volumes = source.getBookVolumesFlow("book")
        assertTrue(events.isEmpty())
        chapters.toList()
        volumes.toList()
        val replacement = mockk<indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource>()
        coEvery { replacement.getChapterContent("chapter", "book", WebDataSourcePriority.Default) } returns Err(error)
        coEvery { replacement.getBookVolumes("book", WebDataSourcePriority.Default) } returns Err(error)
        fixture.activeRemote = replacement
        assertEquals(listOf(Ok(localChapter.copy(title = "processed:local"))), chapters.toList())
        assertEquals(1, volumes.toList().size)
        coVerify(exactly = 1) { replacement.getChapterContent("chapter", "book", WebDataSourcePriority.Default) }
        coVerify(exactly = 1) { replacement.getBookVolumes("book", WebDataSourcePriority.Default) }
        // A previous collection's cache hit must not hide this collection's cache miss.
        coEvery { fixture.local.getChapterContent(chapter.storageKey) } returns null
        coEvery { fixture.local.getBookVolumes(book.storageKey) } returns null
        assertEquals(listOf(Err(error)), chapters.toList())
        assertEquals(listOf(Err(error)), volumes.toList())
        coVerify(exactly = 3) { fixture.local.getChapterContent(chapter.storageKey) }
        coVerify(exactly = 3) { fixture.local.getBookVolumes(book.storageKey) }
    }

    @Test
    fun preloadUsesRequestedPriorityAndStoresRawWithoutReadingOrProcessingLocalContent() = runTest {
        source().preloadChapterContent("chapter", "book", WebDataSourcePriority.Low)
        assertEquals(listOf("remote", "store:remote"), events)
        coVerify(exactly = 1) { fixture.remote.getChapterContent("chapter", "book", WebDataSourcePriority.Low) }
        coVerify(exactly = 0) { fixture.local.getChapterContent(any()) }
        verify(exactly = 0) { fixture.text.processChapterContent(any(), any()) }
        coEvery { fixture.remote.getChapterContent(any(), any(), any()) } returns Err(error)
        source().preloadChapterContent("chapter", "book")
        coVerify(exactly = 1) { fixture.local.updateChapterContent(any()) }
        coVerify(exactly = 1) { fixture.remote.getChapterContent("chapter", "book", WebDataSourcePriority.Default) }
    }

    @Test
    fun textProcessingFailurePropagatesAndStopsRemoteFetching() = runTest {
        val failure = IllegalStateException("processor failed")
        every { fixture.text.processChapterContent(any(), any()) } throws failure
        val actual = runCatching { source().getChapterContentFlow("chapter", "book").collect() }.exceptionOrNull()
        assertSame(failure, actual)
        coVerify(exactly = 0) { fixture.remote.getChapterContent(any(), any(), any()) }
    }
}

class BookRepositoryChapterSourceTest : ChapterSourceContractTest() {
    override fun source(): ChapterSource {
        val repository = fixture.repository()
        return object : ChapterSource {
            override fun getBookVolumesFlow(id: String, priority: WebDataSourcePriority) =
                repository.getBookVolumesFlow(id, priority)

            override fun getChapterContentFlow(chapterId: String, bookId: String, priority: WebDataSourcePriority) =
                repository.getChapterContentFlow(chapterId, bookId, priority)

            override suspend fun preloadChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) =
                repository.preloadChapterContent(chapterId, bookId, priority)
        }
    }
}

class ChapterRepositoryTest : ChapterSourceContractTest() {
    override fun source(): ChapterSource = fixture.chapterRepository()
}
