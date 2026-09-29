package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import hnovel.content.RuleSourceFixture
import indi.renakoni.nextvol.data.book.PartialBookVolumesException
import indi.renakoni.nextvol.data.book.availableVolumes
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleDirectoryFailureTest {
    @Test fun partialDirectoryStaysAnErrorButEarlierChaptersCanBeReadAndRetried(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            var failLaterPage = true
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (failLaterPage && request.path == "/toc/2") MockResponse().setResponseCode(502)
                    else original.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to
                JsonObject(raw.getValue("ruleContent").jsonObject - "nextContentUrl"))) }).use { source ->
                val adapter = RuleWebBookDataSource(Identifier("rules", "directory"), source)
                val id = fixture.server.url("/book/one").toString()
                assertTrue(adapter.getBookInformation(id).isOk)
                val result = adapter.getBookVolumes(id)
                assertTrue(result.isErr)
                assertNull(result.get())
                assertEquals(502, (result.getError()!!.throwable as PartialBookVolumesException).httpStatus)
                val chapter = result.availableVolumes()!!.volumes.single().chapters.single()
                assertEquals("One", chapter.title)
                val content = adapter.getChapterContent(chapter.id, id).getOrElse { error(it.toString()) }
                assertEquals(chapter.id, content.id)
                assertTrue(content.content.toString().contains("first"))
                failLaterPage = false
                val complete = adapter.getBookVolumes(id).getOrElse { error(it.toString()) }
                assertEquals(listOf("One", "Two"), complete.volumes.single().chapters.map { it.title })
            }
        }
    }
}
