package indi.dmzz_yyhyy.lightnovelreader.ui.home

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.bookshelfNavigation
import indi.dmzz_yyhyy.lightnovelreader.ui.home.categories.categoriesDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.exploreNavigation
import indi.dmzz_yyhyy.lightnovelreader.ui.home.reading.readingNavigation
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.settingsNavigation
import indi.dmzz_yyhyy.lightnovelreader.utils.currentMainRoute
import indi.dmzz_yyhyy.lightnovelreader.utils.isResumed
import io.nightfish.lightnovelreader.api.Route
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.homeNavigation(sharedTransitionScope: SharedTransitionScope) {
    navigation<Route.Main>(
        startDestination = Route.Main.Reading
    ) {
        readingNavigation(sharedTransitionScope)
        exploreNavigation()
        categoriesDestination()
        bookshelfNavigation(sharedTransitionScope)
        settingsNavigation()
    }
}

@Suppress("unused")
fun NavController.navigateToHomeNavigation() {
    navigate(Route.Main)
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun HomeNavigateBar(
    selectedRoute: Any?,
    controller: NavController,
) {
    val isReading = selectedRoute is Route.Main.Reading
    val isBookshelf = selectedRoute is Route.Main.Bookshelf
    val isExploration = selectedRoute is Route.Main.Explore
    val isCategories = selectedRoute is Route.Main.Categories

    val avdReading = AnimatedImageVector.animatedVectorResource(R.drawable.animated_book)
    val avdShelf = AnimatedImageVector.animatedVectorResource(R.drawable.animated_bookshelf)
    val avdExplore = AnimatedImageVector.animatedVectorResource(R.drawable.animated_explore)


    NavigationBar(
        windowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
        )
    ) {
        NavigationBarItem(
            selected = isReading,
            onClick = { controller.navigateToMainRoot(Route.Main.Reading) },
            icon = { Icon(painter = rememberAnimatedVectorPainter(avdReading, isReading), null) },
            label = { Text(stringResource(R.string.nav_reading), maxLines = 1) }
        )
        NavigationBarItem(
            selected = isBookshelf,
            onClick = { controller.navigateToMainRoot(Route.Main.Bookshelf) },
            icon = { Icon(painter = rememberAnimatedVectorPainter(avdShelf, isBookshelf), null) },
            label = { Text(stringResource(R.string.nav_bookshelf), maxLines = 1) }
        )
        NavigationBarItem(
            selected = isExploration,
            onClick = { controller.navigateToMainRoot(Route.Main.Explore) },
            icon = { Icon(painter = rememberAnimatedVectorPainter(avdExplore, isExploration), null) },
            label = { Text(stringResource(R.string.nav_explore), maxLines = 1) }
        )
        NavigationBarItem(
            selected = isCategories,
            onClick = { controller.navigateToMainRoot(Route.Main.Categories()) },
            icon = { Icon(painterResource(R.drawable.view_list_24px), null) },
            label = { Text(stringResource(R.string.categories_title), maxLines = 1) }
        )
    }
}

internal const val CATEGORY_SOURCE_REQUEST = "category.sourceRequest"

/** Bottom roots restore their own stack. An explicit category shortcut changes only its source. */
internal fun <T : Any> NavController.navigateToMainRoot(route: T) {
    if (!isResumed()) return
    val current = currentDestination.currentMainRoute()
    val sameRoot = current == route || (current is Route.Main.Categories && route is Route.Main.Categories)
    if (!sameRoot) navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) { saveState = true }
    }
    if (route is Route.Main.Categories && route.namespace != null && route.sourceId != null) {
        getBackStackEntry<Route.Main.Categories>().savedStateHandle[CATEGORY_SOURCE_REQUEST] = Json.encodeToString<Route.Main.Categories>(route)
    }
}
