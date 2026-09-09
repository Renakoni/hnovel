package indi.dmzz_yyhyy.lightnovelreader.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceContentPipelineTest {
    @Test fun directoryFollowsCursorsDeduplicatesAndSorts() = runBlocking {
        val calls = mutableListOf<String?>()
        val executor = object : SourcePipelineExecutor {
            override suspend fun search(c: PipelineContext, k: String) = PipelineResult.Success(emptyList<PipelineBook>())
            override suspend fun information(c: PipelineContext, id: String) = PipelineResult.Failure(PipelineFailure.LoginRequired)
            override suspend fun directory(c: PipelineContext, id: String, cursor: String?): PipelineResult<Page<PipelineChapter>> {
                calls += cursor
                return if (cursor == null) PipelineResult.Success(Page(listOf(PipelineChapter("2", id, "b", 2)), "next"))
                else PipelineResult.Success(Page(listOf(PipelineChapter("1", id, "a", 1)), null))
            }
            override suspend fun content(c: PipelineContext, ch: PipelineChapter) = PipelineResult.Success(PipelineContent(ch.id, "body"))
        }
        val result = SourceContentPipeline(executor).directory(PipelineContext(Identifier("x", "s"), "r", 0), "book")
        assertEquals(listOf(null, "next"), calls)
        assertEquals(listOf("1", "2"), (result as PipelineResult.Success).value.map { it.id })
    }

    @Test fun repeatedChapterOnLaterPageIsRejected() = runBlocking {
        val executor = object : SourcePipelineExecutor {
            override suspend fun search(c: PipelineContext, k: String) = PipelineResult.Success(emptyList<PipelineBook>())
            override suspend fun information(c: PipelineContext, id: String) = PipelineResult.Failure(PipelineFailure.LoginRequired)
            override suspend fun directory(c: PipelineContext, id: String, cursor: String?) =
                if (cursor == null) PipelineResult.Success(Page(listOf(PipelineChapter("1", id, "a", 1)), "next"))
                else PipelineResult.Success(Page(listOf(PipelineChapter("1", id, "a", 1)), null))
            override suspend fun content(c: PipelineContext, ch: PipelineChapter) = PipelineResult.Success(PipelineContent(ch.id, "body"))
        }
        assertTrue(SourceContentPipeline(executor).directory(PipelineContext(Identifier("x", "s"), "r", 0), "b") is PipelineResult.Failure)
    }

    @Test fun repeatedCursorIsRejectedEvenWhenPageHasNoItems() = runBlocking {
        val executor = object : SourcePipelineExecutor {
            override suspend fun search(c: PipelineContext, k: String) = PipelineResult.Success(emptyList<PipelineBook>())
            override suspend fun information(c: PipelineContext, id: String) = PipelineResult.Failure(PipelineFailure.LoginRequired)
            override suspend fun directory(c: PipelineContext, id: String, cursor: String?) = PipelineResult.Success(Page(emptyList(), "same"))
            override suspend fun content(c: PipelineContext, ch: PipelineChapter) = PipelineResult.Success(PipelineContent(ch.id, "body"))
        }
        assertTrue(SourceContentPipeline(executor).directory(PipelineContext(Identifier("x", "s"), "r", 0), "b") is PipelineResult.Failure)
    }
}
