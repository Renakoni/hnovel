package indi.renakoni.nextvol.ui.bangumi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
        LaunchedEffect(state.confirmed) { if (state.confirmed) nav.popBackStackIfResumed() }
        BangumiScreen(state, viewModel.bookId, nav::popBackStackIfResumed,
            onAccount = { nav.navigate(BangumiRoute()) },
            onBook = { id -> nav.navigate(BangumiRoute(id)) },
            onConnect = viewModel::connect, onDisconnect = viewModel::disconnect,
            onQuery = viewModel::query, onSearch = { viewModel.search() }, onMore = { viewModel.search(true) },
            onChoose = viewModel::choose, onSync = viewModel::sync,
            onMapping = viewModel::mapping, onComplete = viewModel::complete, onBaseline = viewModel::baseline,
            onPrivate = viewModel::privateCollection, onConfirm = viewModel::confirm, onDismiss = viewModel::dismissPreview)
    }
}
