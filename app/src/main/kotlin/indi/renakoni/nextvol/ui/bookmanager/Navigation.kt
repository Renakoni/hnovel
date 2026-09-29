package indi.renakoni.nextvol.ui.bookmanager

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.downloadSubmissionText
import indi.renakoni.nextvol.ui.book.download.navigateToBookDownload
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.bookManager() {
    composable<Route.BookManager> {
        val navController = LocalNavController.current
        val snackbarHostState = LocalSnackbarHost.current
        val context = LocalContext.current
        val viewModel = hiltViewModel<BookManagerViewModel>()
        val uiState = viewModel.localBookManagerUiState
        val clearedItemsText = stringResource(R.string.book_manager_cleared_items)
        LaunchedEffect(viewModel.clearedItemsFlow) {
            viewModel.clearedItemsFlow.collect { count ->
                snackbarHostState.showSnackbar(
                    clearedItemsText.format(count),
                    withDismissAction = true
                )
            }
        }
        LaunchedEffect(viewModel.downloadSubmissions) {
            viewModel.downloadSubmissions.collect { result ->
                snackbarHostState.showSnackbar(context.downloadSubmissionText(result), withDismissAction = true)
            }
        }
        uiState.openStorageOverview = {
            navController.navigate(Route.StorageManager)
        }
        uiState.openBookDetailScreen = { id ->
            navController.navigate(Route.Book.Detail(id))
        }
        BookManagerScreen(
            onClickBack = navController::popBackStackIfResumed,
            downloadItemIdList = viewModel.downloadItemIdList,
            uiState = uiState,
            onClickCancel = viewModel::onClickCancel,
            onClickRetry = { item ->
                if (item.type == DownloadType.CACHE && item.status?.task?.status == DownloadTaskStatus.Complete)
                    navController.navigateToBookDownload(item.bookId, refresh = true)
                else viewModel.onClickRetry(item)
            },
            onOpenDownload = { navController.navigateToBookDownload(it) },
            onClickClearCompleted = viewModel::onClickClearCompleted
        )
    }
}

fun NavController.navigateToDownloadManager() {
    if (!this.isResumed()) return
    navigate(Route.BookManager)
}
