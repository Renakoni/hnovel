package indi.dmzz_yyhyy.lightnovelreader.data.book

import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceBrowseIngressTest {
    @Test fun searchCapturesSourceBeforeCollectionAndQualifiesBothResultKinds() = runTest {
        val id = Identifier("fixture", "a")
        val source = mockk<ProxyWebBookDataSource> {
            every { this@mockk.id } returns id
            every { searchProvider.search(any(), any()) } returns flowOf(
                SearchResult.SingleBook("same"), SearchResult.MultipleBook("same"))
        }
        var selected = source
        val provider = mockk<WebBookDataSourceProvider> { every { value } answers { selected } }
        val flow = ExploreRepository(provider).search(mockk<SearchType>(), "same title")
        selected = mockk()
        val results = flow.toList()
        val key = SourceBookId(id, "same").storageKey
        assertEquals(key, (results[0] as SearchResult.SingleBook).bookId)
        assertEquals(key, (results[1] as SearchResult.MultipleBook).bookId)
    }
}
