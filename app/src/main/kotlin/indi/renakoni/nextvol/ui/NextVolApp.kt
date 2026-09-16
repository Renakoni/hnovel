package indi.renakoni.nextvol.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.rememberNavController
import indi.renakoni.nextvol.ui.dialog.UpdatesAvailableDialogViewModel
import indi.renakoni.nextvol.ui.dialog.navigateToPluginInstallerDialog
import indi.renakoni.nextvol.ui.dialog.navigateUpdatesAvailableDialog
import indi.renakoni.nextvol.ui.navigation.NextVolNavHost
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import kotlinx.coroutines.flow.Flow
import hnovel.imports.sourceImportUrl
import io.nightfish.lightnovelreader.api.Route

@Composable
fun NextVolApp(
    onBuildNavHost: NavGraphBuilder.() -> Unit,
    onReaderActiveChanged: (Boolean) -> Unit,
    readerStyle: ReaderStyle,
    intentFlow: Flow<Intent>,
) {
    val navController = rememberNavController()
    val updatesAvailableDialogViewModel = hiltViewModel<UpdatesAvailableDialogViewModel>()
    val available by updatesAvailableDialogViewModel.availableFlow.collectAsStateWithLifecycle(false)
    LaunchedEffect(available) {
        if (available) {
            updatesAvailableDialogViewModel.resetAvailable()
            navController.navigateUpdatesAvailableDialog()
        }
    }
    LaunchedEffect(Unit) {
        intentFlow.collect { intent ->
            if (intent.action == Intent.ACTION_VIEW) {
                val uri = intent.data ?: return@collect
                if (uri.scheme == "legado") {
                    sourceImportUrl(uri.toString())?.let { navController.navigate(Route.Main.Settings.SourceImport(it)) }
                } else navController.navigateToPluginInstallerDialog(uri.toString())
            }
        }
    }
    NextVolNavHost(
        navController = navController,
        onBuildNavHost = onBuildNavHost,
        onReaderActiveChanged = onReaderActiveChanged,
        readerStyle = readerStyle
    )
}
