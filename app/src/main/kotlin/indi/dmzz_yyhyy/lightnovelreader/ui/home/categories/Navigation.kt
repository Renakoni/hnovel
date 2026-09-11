package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.navigateToBookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryResultsScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryResultsViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.discoveryEnvironment
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryAction
import kotlinx.serialization.json.Json
import java.util.UUID

fun NavGraphBuilder.categoriesDestination() {
    composable<Route.Main.Categories> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<CategoriesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        val environment = discoveryEnvironment()
        LifecycleStartEffect(model, environment) {
            model.environment(environment)
            model.setActive(true)
            onStopOrDispose {
                // The owned browser opens an Activity, not a navigation destination. Do not cancel
                // it merely because it covers this page; navigating elsewhere still cancels it.
                model.setActive(false, retainBrowser = nav.currentBackStackEntry?.id == entry.id)
            }
        }
        DisposableEffect(model, nav, entry) {
            onDispose {
                // Navigation can remove the composition after onStop already retained the browser.
                if (nav.currentBackStackEntry?.id != entry.id) model.setActive(false)
            }
        }
        LaunchedEffect(model) {
            model.commands.collect { command ->
                if (model.accepts(command)) when (val action = command.action) {
                    is DiscoveryAction.Results -> nav.navigate(Route.Main.DiscoveryResults(command.source.namespace,
                        command.source.id, action.target, action.title, UUID.randomUUID().toString(),
                        filtersJson = Json.encodeToString(command.values)))
                    DiscoveryAction.Login, DiscoveryAction.Settings -> nav.navigate(Route.Main.Settings.SourceDetail(
                        command.source.namespace, command.source.id, action == DiscoveryAction.Login))
                    is DiscoveryAction.Browser -> model.openBrowser(command)
                }
            }
        }
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
