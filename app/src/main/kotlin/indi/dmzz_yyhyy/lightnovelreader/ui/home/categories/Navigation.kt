package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.navigateToBookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryPageEffects
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryResultsScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryResultsViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.discoveryEnvironment
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.categoriesDestination() {
    composable<Route.Main.Categories> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<CategoriesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        DiscoveryPageEffects(model, entry)
        CategoriesScreen(state, model::select,
            onCategory = { category -> model.result(category)?.let { nav.navigate(it) } },
            model::scroll, model::refresh,
            onManageSources = { nav.navigate(Route.Main.Settings.Sources) },
            onSettings = { nav.navigate(Route.Main.Settings) }, onBack = { nav.popBackStack() },
            onInput = { id, value -> model.interact(id, value) }, onAction = { id, longClick -> model.interact(id, longClick = longClick) })
    }
    composable<Route.Main.DiscoveryResults> {
        val nav = LocalNavController.current
        val model = hiltViewModel<DiscoveryResultsViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        val environment = discoveryEnvironment()
        LifecycleStartEffect(model, environment) {
            model.environment(environment)
            model.setActive(true)
            onStopOrDispose { model.setActive(false) }
        }
        DiscoveryResultsScreen(state, model::filter, model::loadMore, model::refresh, model::scroll,
            onBook = { nav.navigateToBookDetailDestination(it.storageKey) },
            onManageSources = { nav.navigate(Route.Main.Settings.Sources) },
            onSettings = { nav.navigate(Route.Main.Settings) }, onBack = { nav.popBackStack() })
    }
}
