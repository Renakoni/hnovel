package indi.renakoni.nextvol.defaultplugin.wenku8

import android.app.Application
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.defaultplugin.wenku8.book.Wenku8WebsiteDataSource
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class Wenku8SearchPageTest {
    @Test fun pageRetainsCardMetadataAndDoesNotStartTheNextRequest() = runBlocking {
        val api = spyk(Wenku8Api { error("Network is replaced by the fixture") })
        val calls = mutableListOf<String>()
        coEvery { api.getWithWenku8Cookie(any()) } answers {
            calls += firstArg<String>()
            Ok(Jsoup.parse("""
                <div id="content"><table><tr><td><div>
                    <div><a href="/book/42.htm" title="Book title"><img src="https://fixture.invalid/42.jpg"></a></div>
                    <div><b>Book title</b><p>作者:Author/分类:文库</p><p>更新:2026-09-09/字数:120K/已完结</p>
                    <p><span>校园</span></p><p>简介:Description</p></div>
                </div></td></tr></table></div><div id="pagelink"><em>1/3</em></div>
            """))
        }
        try {
            val source = Wenku8WebsiteDataSource("https://fixture.invalid", api)
            val first = source.searchPage("articlename", "校园", 1)
            assertEquals(1, calls.size)
            assertTrue(calls.single().contains("searchkey=%D0%A3%D4%B0&page=1"))
            assertEquals(2, first.nextPage)
            assertEquals("42", first.books.single().bookId)
            assertEquals("Book title", first.books.single().information!!.title)
            assertEquals("Author", first.books.single().information!!.author)
            assertEquals("Description", first.books.single().information!!.description)
            source.searchPage("articlename", "校园", 2)
            assertEquals(2, calls.size)
            assertTrue(calls.last().endsWith("page=2"))
        } finally { api.close() }
    }
}
