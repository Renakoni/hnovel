package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.explore

import androidx.core.net.toUri
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.Wenku8Api
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jsoup.nodes.Document

class Wenku8AllExploreTapPage(
    val host: String,
    val wenku8Api: Wenku8Api
): ExploreTapPageDataSource {
    override val title = "全部"

    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        val rows = mutableListOf<ExploreBooksRow>()
        rows.add(getAllBookBooksRow().copy(expandable = true, expandedPageDataSourceId = "allBook"))
        emit(rows.toList())
        rows.add(getTopListBookBooksRow("热门轻小说", "allvisit"))
        emit(rows.toList())
        rows.add(getTopListBookBooksRow("动画化作品", "anime"))
        emit(rows.toList())
        rows.add(getTopListBookBooksRow("今日更新", "lastupdate"))
        emit(rows.toList())
        rows.add(getTopListBookBooksRow("新书一览", "postdate"))
        emit(rows.toList())
        rows.add(getCompletedBooksRow().copy(expandable = true, expandedPageDataSourceId = "allCompletedBook"))
        emit(rows.toList())
    }

    private suspend fun getCompletedBooksRow(): ExploreBooksRow {
        val soup = wenku8Api.getWithWenku8Cookie("${host}/modules/article/articlelist.php?fullflag=1").component1()
        return getBooksRow(soup, "完结全本").copy(
            expandable = true,
            expandedPageDataSourceId = "allBook"
        )
    }

    private suspend fun getTopListBookBooksRow(title: String, sort: String): ExploreBooksRow {
        val soup = wenku8Api.getWithWenku8Cookie("${host}/modules/article/toplist.php?sort=$sort").component1()
        return getBooksRow(soup, title).copy(
            expandable = true,
            expandedPageDataSourceId = "${sort}Book"
        )
    }

    private suspend fun getAllBookBooksRow(): ExploreBooksRow {
        val soup = wenku8Api.getWithWenku8Cookie("${host}/modules/article/articlelist.php").component1()
        return getBooksRow(soup, "轻小说列表")
    }

    private fun getBooksRow(soup: Document?, title: String): ExploreBooksRow {
        return ExploreBooksRow(
            title = title,
            bookList = Wenku8DiscoveryParser.cards(soup, host).take(6).map { card ->
                val book = card.book
                ExploreDisplayBook(book.remoteId, book.title, book.author, book.coverUrl.toUri())
            },
        )
    }
}
