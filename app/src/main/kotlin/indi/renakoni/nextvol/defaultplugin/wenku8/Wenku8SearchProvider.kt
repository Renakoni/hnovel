package indi.renakoni.nextvol.defaultplugin.wenku8

import indi.renakoni.nextvol.defaultplugin.wenku8.book.BookRequestDispatcher
import indi.renakoni.nextvol.data.explore.PagedSearchProvider
import indi.renakoni.nextvol.defaultplugin.wenku8.search.Wenku8SearchSession
import indi.renakoni.nextvol.defaultplugin.wenku8.search.Wenku8SearchSupport
import indi.renakoni.nextvol.data.web.SourceRequestOwner
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.AbstractSearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import java.util.UUID

class Wenku8SearchProvider(
    val dispatcher: BookRequestDispatcher,
    private val support: Wenku8SearchSupport? = null,
): AbstractSearchProvider(), PagedSearchProvider {
    private val sessions = linkedMapOf<String, Wenku8SearchSession>()
    override suspend fun searchPage(type: SearchType, keyword: String, page: Int, query: String?) =
        searchPageUpdates(type, keyword, page, query).last()

    override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
        if (support == null) {
            emit(dispatcher.source.first().searchPage(type.type, keyword, page))
        } else {
            val owner = currentCoroutineContext()[SourceRequestOwner]?.id
            val key = "$owner:${query ?: UUID.randomUUID()}:${type.type}:$keyword"
            val session = synchronized(sessions) {
                sessions.getOrPut(key) { Wenku8SearchSession(support, type.type, keyword) { name, number ->
                    dispatcher.source.first().searchPage(type.type, name, number)
                } }.also { while (sessions.size > 8) sessions.remove(sessions.keys.first()) }
            }
            emitAll(session.page(page))
        }
    }

    override fun search(
        searchType: SearchType,
        keyword: String
    ): Flow<SearchResult> = flow {
        if (support == null) { emitAll(dispatcher.search(searchType.type, keyword)); return@flow }
        val query = UUID.randomUUID().toString()
        val seen = hashSetOf<String>()
        var page: Int? = 1
        var failure: Throwable? = null
        while (page != null && seen.size < 1000) {
            searchPageUpdates(searchType, keyword, page!!, query).collect { batch ->
                batch.books.forEach { if (seen.add(it.bookId)) emit(it) }
                if (batch.complete) { page = batch.nextPage; failure = batch.failure }
            }
            if (failure != null) break
        }
        emit(failure?.let { SearchResult.Error(it) } ?: SearchResult.End())
    }.catch { error ->
        currentCoroutineContext().ensureActive()
        emit(SearchResult.Error(error))
    }

    init {
        registerSearchType("articlename", "按书名搜索".local(), "请输入书本名称".local())
        registerSearchType("author", "按作者名搜索".local(), "请输入作者名称".local())
    }
}
