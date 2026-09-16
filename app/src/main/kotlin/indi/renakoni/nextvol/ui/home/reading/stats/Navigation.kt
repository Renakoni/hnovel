package indi.renakoni.nextvol.ui.home.reading.stats

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.navigateToReadingStatsDetailedDestination
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.readingStatsDetailedDestination
import io.nightfish.lightnovelreader.api.Route
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed

fun NavGraphBuilder.readingStatsNavigation() {
    navigation<Route.Main.Reading.Stats>(
        startDestination = Route.Main.Reading.Stats.Overview
    ) {
        readingStatsOverviewDestination()
        readingStatsDetailedDestination()
    }
}

fun NavGraphBuilder.readingStatsOverviewDestination() {
    composable<Route.Main.Reading.Stats.Overview> {
        val navController = LocalNavController.current
        val statsOverviewViewModel = hiltViewModel<StatsOverviewViewModel>()
        StatsOverviewScreen(
            onClickBack = navController::popBackStackIfResumed,
            viewModel = statsOverviewViewModel,
            onClickDetailScreen = navController::navigateToReadingStatsDetailedDestination
        )
    }
}

fun NavController.navigateToReadingStatsDestination() {
    if (!this.isResumed()) return
    navigate(Route.Main.Reading.Stats)
}
