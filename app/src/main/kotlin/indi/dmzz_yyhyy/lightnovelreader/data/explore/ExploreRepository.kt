package indi.dmzz_yyhyy.lightnovelreader.data.explore

import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExploreRepository @Inject constructor(
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
) {
    val sourceSnapshot get() = webBookDataSourceProvider.value
    val searchTypes get() = webBookDataSourceProvider.value.searchProvider.searchTypes
    val explorePageProvider get() = webBookDataSourceProvider.value.explorePageProvider

    fun search(searchType: SearchType, keyword: String): Flow<SearchResult> {
        val source = sourceSnapshot
        return source.searchProvider.search(searchType, keyword).map { result ->
            when (result) {
                is SearchResult.SingleBook -> SearchResult.SingleBook(SourceBookId(source.id, result.bookId).storageKey)
                is SearchResult.MultipleBook -> SearchResult.MultipleBook(SourceBookId(source.id, result.bookId).storageKey)
                else -> result
            }
        }
    }

    fun getSuggestions(history: List<String>, keyword: String): List<String> = webBookDataSourceProvider.value.searchProvider.getSearchSuggestions(history, keyword)
}