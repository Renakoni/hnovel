package indi.dmzz_yyhyy.lightnovelreader.data.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import kotlinx.coroutines.CoroutineScope

/** Lifecycle guard for the old page API; the data-only replacement belongs to VNR-05. */
internal fun guardExploreProvider(runtime: SourceRuntime, provider: ExplorePageProvider): ExplorePageProvider = when (provider) {
    is ExplorePageProvider.DefaultExplorePageProvider -> object : ExplorePageProvider.DefaultExplorePageProvider {
        override val explorePageIdList get() = run {
            runtime.checkAvailable()
            provider.explorePageIdList.toList()
        }
        override val exploreTapPageDataSourceMap by lazy {
            provider.exploreTapPageDataSourceMap.mapValues { (_, page) ->
                object : ExploreTapPageDataSource {
                    override val title get() = page.title
                    override fun getRowsFlow() = runtime.observe { page.getRowsFlow() }
                }
            }
        }
        override val exploreExpandedPageDataSourceMap by lazy {
            provider.exploreExpandedPageDataSourceMap.mapValues { (_, page) ->
                object : ExploreExpandedPageDataSource {
                    override val title get() = page.title
                    override val filters get() = run { runtime.checkAvailable(); page.filters }
                    override fun loadMore() {
                        runtime.checkAvailable()
                        page.loadMore()
                    }
                    override fun getResultFlow() = runtime.observe { page.getResultFlow() }
                }
            }
        }
    }
    is ExplorePageProvider.CustomExplorePageProvider<*> -> object : ExplorePageProvider.CustomExplorePageProvider<Any?> {
        override val uiState get() = provider.uiState
        override fun init(viewModelScope: CoroutineScope) {
            provider.init(runtime.ownedScope(viewModelScope))
        }
        @Composable
        override fun Content(nestedScrollConnection: NestedScrollConnection) {
            if (runtime.isAvailable) provider.Content(nestedScrollConnection)
        }
    }
}
