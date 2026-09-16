package indi.renakoni.nextvol.ui.home.settings.pluginmanager.applist

import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.ui.home.settings.pluginmanager.PluginManagerViewModel
import io.nightfish.lightnovelreader.api.Route
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.settingsPluginAppListDestination() {
    composable<Route.Main.Settings.PluginManager.AppList> { navBackStackEntry ->
        val navController = LocalNavController.current
        val parentEntry = remember(navBackStackEntry) {
            navBackStackEntry.destination.parent?.route
                ?.let(navController::getBackStackEntry)
        }
        val viewModel = hiltViewModel<PluginManagerViewModel>(parentEntry ?: navBackStackEntry)
        PluginAppListScreen(
            appPluginList = viewModel.scannedPluginApps,
            onRefresh = {},
            onClickBack = navController::popBackStackIfResumed
        )
    }
}

fun NavController.navigateToSettingsPluginAppListDestination() {
    navigate(Route.Main.Settings.PluginManager.AppList)
}
