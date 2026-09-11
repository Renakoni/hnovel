package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.navigateToBookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryResultsScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryResultsViewModel
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.categoriesDestination() {
    composable<Route.Main.Categories> {
        val nav = LocalNavController.current
        val model = hiltViewModel<CategoriesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        LifecycleStartEffect(model) {
            model.setActive(true)
            onStopOrDispose { model.setActive(false) }
        }
        CategoriesScreen(state, model::select,
            onCategory = { category -> model.result(category)?.let { nav.navigate(it) } },
            model::scroll, model::refresh,
            onManageSources = { nav.navigate(Route.Main.Settings.Sources) },
            onSettings = { nav.navigate(Route.Main.Settings) }, onBack = { nav.popBackStack() })
    }
    composable<Route.Main.DiscoveryResults> {
        val nav = LocalNavController.current
        val model = hiltViewModel<DiscoveryResultsViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        LifecycleStartEffect(model) {
            model.setActive(true)
            onStopOrDispose { model.setActive(false) }
        }
        DiscoveryResultsScreen(state, model::filter, model::loadMore, model::refresh, model::scroll,
            onBook = { nav.navigateToBookDetailDestination(it.storageKey) },
            onManageSources = { nav.navigate(Route.Main.Settings.Sources) },
            onSettings = { nav.navigate(Route.Main.Settings) }, onBack = { nav.popBackStack() })
    }
}
