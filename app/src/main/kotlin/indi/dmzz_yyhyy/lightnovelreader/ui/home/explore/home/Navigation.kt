package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.home

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.navigateToBookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryPageEffects
import indi.dmzz_yyhyy.lightnovelreader.ui.home.navigateToMainRoot
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.navigateToSettingsDestination
import indi.dmzz_yyhyy.lightnovelreader.utils.isResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.exploreHomeDestination() {
    composable<Route.Main.Explore.Home> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<ExploreHomeViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        DiscoveryPageEffects(model, entry)
        ExploreHomeScreen(state, model::select, model::scroll, model::refresh,
            onMore = { model.more(it)?.let { route -> nav.navigate(route) } },
            onBook = { nav.navigateToBookDetailDestination(it.storageKey) },
            onSearch = { model.search()?.let { nav.navigate(it) } },
            onCategories = { model.categories()?.let { nav.navigateToMainRoot(it) } },
            onManageSources = { nav.navigate(Route.Main.Settings.Sources) },
            onInput = { id, value -> model.interact(id, value) },
            onAction = { id, longClick -> model.interact(id, longClick = longClick) },
            onSettings = nav::navigateToSettingsDestination)
    }
}

fun NavController.navigateToExploreHomeDestination() {
    if (!isResumed()) return
    navigate(Route.Main.Explore.Home)
}
