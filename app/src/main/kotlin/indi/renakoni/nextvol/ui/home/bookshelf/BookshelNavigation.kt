package indi.renakoni.nextvol.ui.home.bookshelf

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.renakoni.nextvol.ui.home.bookshelf.edit.bookshelfEditDestination
import indi.renakoni.nextvol.ui.home.bookshelf.home.bookshelfHomeDestination
import indi.renakoni.nextvol.ui.home.bookshelf.reorder.bookshelfReorderDestination
import io.nightfish.lightnovelreader.api.Route

@OptIn(ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.bookshelfNavigation(sharedTransitionScope: SharedTransitionScope) {
    navigation<Route.Main.Bookshelf>(
        startDestination = Route.Main.Bookshelf.Home
    ) {
        bookshelfHomeDestination(sharedTransitionScope)
        bookshelfEditDestination()
        bookshelfReorderDestination()
    }
}

@Suppress("unused")
fun NavController.navigateToBookshelfNavigation() {
    navigate(Route.Main.Bookshelf)
}