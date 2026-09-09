package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.explore

import androidx.core.net.toUri
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.Wenku8Api
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class Wenku8HomeExploreTapPage(
    val host: String,
    val wenku8Api: Wenku8Api
): ExploreTapPageDataSource {
    override val title = "首页"

    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        val rows = mutableListOf<ExploreBooksRow>()
        val soup = wenku8Api.getWithWenku8Cookie(host).component1()
        Wenku8DiscoveryParser.home(soup, host).forEach { section ->
            rows.add(ExploreBooksRow(section.title, section.books.map {
                ExploreDisplayBook(it.remoteId, it.title, it.author, it.coverUrl.toUri())
            }))
            emit(rows.toList())
        }
    }
}
