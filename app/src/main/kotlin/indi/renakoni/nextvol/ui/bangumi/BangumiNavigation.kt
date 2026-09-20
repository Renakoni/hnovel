package indi.renakoni.nextvol.ui.bangumi

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.serialization.Serializable

@Serializable
data class BangumiRoute(val bookId: String? = null)

fun NavGraphBuilder.bangumiDestination() {
    composable<BangumiRoute> {
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<BangumiViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        BangumiScreen(state, viewModel.bookId, nav::popBackStackIfResumed,
            onAccount = { nav.navigate(BangumiRoute()) { launchSingleTop = true } },
            onBook = { id -> nav.navigate(BangumiRoute(id)) { launchSingleTop = true } },
            onConnect = viewModel::connect, onDisconnect = viewModel::disconnect,
            onQuery = viewModel::query, onSearch = { viewModel.search() }, onMore = { viewModel.search(true) },
            onChoose = viewModel::choose, onUnlink = viewModel::unlink, onRetry = viewModel::retry,
            onMapping = viewModel::mapping, onComplete = viewModel::complete, onBaseline = viewModel::baseline,
            onPrivate = viewModel::privateCollection, onConfirm = viewModel::confirm, onDismiss = viewModel::dismissPreview)
    }
}
