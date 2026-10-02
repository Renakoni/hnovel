package indi.renakoni.nextvol.ui.storagemanager

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.Route
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed

fun NavGraphBuilder.storageManager() {
    composable<Route.StorageManager> {
        val navController = LocalNavController.current
        val viewModel = hiltViewModel<StorageManagerViewModel>()
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
        StorageManagerScreen(
            onClickBack = navController::popBackStackIfResumed,
            uiState = viewModel.uiState,
            clearReadingCache = viewModel::clearReadingCache,
            clearDownloads = viewModel::clearDownloads,
            onOpenBooks = { navController.navigate(indi.renakoni.nextvol.ui.bookmanager.StoredBooksRoute) { launchSingleTop = true } },
        )
    }
}

fun NavController.navigateToStorageManager() {
    if (!this.isResumed()) return
    navigate(Route.StorageManager)
}
