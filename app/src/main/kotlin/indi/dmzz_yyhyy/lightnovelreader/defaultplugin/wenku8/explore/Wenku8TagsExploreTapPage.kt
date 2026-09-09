package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.wenku8.Wenku8Api
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class Wenku8TagsExploreTapPage(
    val host: String,
    val wenku8Api: Wenku8Api
): ExploreTapPageDataSource {
    override val title = "分类"


    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        val soup = wenku8Api.getWithWenku8Cookie("$host/modules/article/tags.php").component1()
            ?: return@flow
        // The legacy tab also exposes the directory without fetching every preview.
        emit(Wenku8DiscoveryParser.categories(soup).filter { wenku8Api.bookTagPage(it.target.removePrefix("tag:")) != null }.map {
            ExploreBooksRow(it.title, emptyList(), true, it.target.removePrefix("tag:"))
        })
    }
}
