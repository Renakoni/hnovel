package indi.renakoni.nextvol.ui.home.settings.licenses

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.nightfish.lightnovelreader.api.Route
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.settingsLicensesDestination() {
    composable<Route.Main.Settings.Licenses> {
        val navController = LocalNavController.current
        LicensesScreen(
            onClickBack = navController::popBackStackIfResumed
        )
    }
}

fun NavController.navigateToSettingsLicensesDestination() {
    navigate(Route.Main.Settings.Licenses)
}
