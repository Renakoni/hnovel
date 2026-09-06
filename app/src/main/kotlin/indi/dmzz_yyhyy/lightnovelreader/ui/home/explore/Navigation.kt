package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded.exploreExpandDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.home.exploreHomeDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search.exploreSearchDestination
import io.nightfish.lightnovelreader.api.Route

@OptIn(ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.exploreNavigation() {
    navigation<Route.Main.Explore>(
        startDestination = Route.Main.Explore.Home,
    ) {
        exploreHomeDestination()
        exploreExpandDestination()
        exploreSearchDestination()
    }
}

@Suppress("unused")
fun NavController.navigateToExploreNavigation() {
    navigate(Route.Main.Explore)
}