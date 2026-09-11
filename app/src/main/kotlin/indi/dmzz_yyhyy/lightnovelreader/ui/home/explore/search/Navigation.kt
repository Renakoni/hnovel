package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search

import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.navigateToBookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.dialog.navigateToAddBookToBookshelfDialog
import indi.dmzz_yyhyy.lightnovelreader.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.exploreSearchDestination() {
    composable<Route.Main.Explore.Search> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<ExploreSearchViewModel>()
        LifecycleStartEffect(model) {
            model.setActive(true)
            onStopOrDispose { model.setActive(false) }
        }
        LaunchedEffect(model, entry) {
            entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.navigation.collect { command ->
                    if (model.accepts(command)) nav.navigateToBookDetailDestination(command.bookId)
                }
            }
        }
        ExploreSearchScreen(
            exploreSearchUiState = model.uiState,
            refresh = model::retry,
            requestAddBookToBookshelf = { nav.navigateToAddBookToBookshelfDialog(it) },
            onClickBack = { nav.popBackStackIfResumed() },
            onChangeSearchType = model::changeSearchType,
            onSearch = model::search,
            onClickDeleteHistory = model::deleteHistory,
            onClickClearAllHistory = model::clearAllHistory,
            onClickBook = { nav.navigateToBookDetailDestination(it) },
            updateSuggestions = model::updateSuggestions,
            onManageSources = {
                nav.navigate(Route.Main.Settings.SourceDetail(model.sourceId.namespace, model.sourceId.id))
            },
        )
    }
}
