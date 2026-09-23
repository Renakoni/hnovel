package indi.renakoni.nextvol.defaultplugin.wenku8

import indi.renakoni.nextvol.defaultplugin.wenku8.book.BookRequestDispatcher
import indi.renakoni.nextvol.data.explore.PagedSearchProvider
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.AbstractSearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.flow.Flow

class Wenku8SearchProvider(
    val dispatcher: BookRequestDispatcher
): AbstractSearchProvider(), PagedSearchProvider {
    override suspend fun searchPage(type: SearchType, keyword: String, page: Int) =
        dispatcher.source.first().searchPage(type.type, keyword, page)

    override fun search(
        searchType: SearchType,
        keyword: String
    ): Flow<SearchResult> {
        return dispatcher.search(searchType.type, keyword)
    }

    init {
        registerSearchType("articlename", "按书名搜索".local(), "请输入书本名称".local())
        registerSearchType("author", "按作者名搜索".local(), "请输入作者名称".local())
    }
}
