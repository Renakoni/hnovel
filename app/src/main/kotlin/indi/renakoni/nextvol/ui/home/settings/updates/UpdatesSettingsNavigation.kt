package indi.renakoni.nextvol.ui.home.settings.updates

import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.update.UpdatePhase
import indi.renakoni.nextvol.ui.dialog.UpdatesAvailableDialogViewModel
import indi.renakoni.nextvol.ui.home.settings.SettingsViewModel
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.settingsUpdatesDestination() {
    composable<Route.Main.Settings.Updates> {
        val navController = LocalNavController.current
        val viewModel = hiltViewModel<SettingsViewModel>()
        val updatesViewModel = hiltViewModel<UpdatesAvailableDialogViewModel>()
        val updatePhase by updatesViewModel.updatePhaseFlow.collectAsStateWithLifecycle(
            UpdatePhase(R.string.update_phase_not_checked)
        )
        UpdatesSettingsScreen(
            updatePhase = stringResource(updatePhase.messageId, *updatePhase.arguments.toTypedArray()),
            settingState = viewModel.settingState,
            checkUpdate = updatesViewModel::checkUpdate,
            onBack = navController::popBackStackIfResumed,
        )
    }
}
