package indi.renakoni.nextvol.data.explore

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType

/** Optional host adapter capability. Existing plugins keep their streaming SearchProvider contract. */
internal interface PagedSearchProvider {
    /** Pages of one user query share [query]; a new query uses a new value. */
    suspend fun searchPage(type: SearchType, keyword: String, page: Int, query: String? = null): SearchPage
}

data class SearchPage(val books: List<SearchResult.MultipleBook>, val nextPage: Int?)
