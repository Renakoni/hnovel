package indi.renakoni.nextvol.ui.home.settings.debug

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import io.nightfish.lightnovelreader.api.Route
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed

fun NavGraphBuilder.settingsDebugDestination() {
    composable<Route.Main.Settings.Debug> {
        val navController = LocalNavController.current
        val viewModel = hiltViewModel<DebugScreenViewModel>()
        DebugScreen(
            onClickBack = navController::popBackStackIfResumed,
            onClickQuery = viewModel::runSQLCommand,
            onClickOpenBook = {
                navController.navigateToBookDetailDestination(it)
            },
            result = viewModel.result
        )
    }
}

fun NavController.navigateToSettingsDebugDestination() {
    if (!this.isResumed()) return
    navigate(Route.Main.Settings.Debug)
}