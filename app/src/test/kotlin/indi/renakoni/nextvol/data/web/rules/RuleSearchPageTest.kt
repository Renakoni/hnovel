package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.explore.ExploreRepository
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleSearchPageTest {
    @Test fun sourceBoundSearchUsesOneRulePageAndCarriesItsPreviewWithoutFetchingDetails() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rules = fixture.source()
            val id = Identifier("search-rule", "a")
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rules), SourceMetadata(
                WebDataSourceItem(id, "Rules", "fixture"), setOf(SourceCapability.Search)))
            try {
                val session = ExploreRepository(registry).open(id).get()!!
                assertTrue(session.hasPages)
                val page = session.page(session.types.first(), "title", 1).first()
                assertEquals(1, fixture.documents.get())
                assertEquals(2, page.nextPage)
                val result = page.books.single()
                assertEquals("Same title", result.information!!.title)
                assertEquals(result.bookId, result.information!!.id)
                assertEquals(id, SourceBookId.fromStorageKey(result.bookId).sourceId)
                assertTrue(fixture.server.takeRequest().path!!.contains("page=1"))
                session.page(session.types.first(), "title", 2).first()
                assertEquals(2, fixture.documents.get())
                assertTrue(fixture.server.takeRequest().path!!.contains("page=2"))
            } finally { registry.unregister(id) }
        }
    }
}
