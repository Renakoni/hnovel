package indi.renakoni.nextvol.data.explore

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType

/** Optional host adapter capability. Existing plugins keep their streaming SearchProvider contract. */
internal interface PagedSearchProvider {
    suspend fun searchPage(type: SearchType, keyword: String, page: Int): SearchPage
}

data class SearchPage(val books: List<SearchResult.MultipleBook>, val nextPage: Int?)
