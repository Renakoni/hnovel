package indi.renakoni.nextvol.ui.home.settings

import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import androidx.work.WorkInfo
import androidx.work.WorkManager
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.ExportContext
import indi.renakoni.nextvol.ui.components.ExportUserDataDialog
import indi.renakoni.nextvol.ui.components.MutableExportContext
import indi.renakoni.nextvol.ui.components.SliderValueDialog
import indi.renakoni.nextvol.ui.dialog.SliderValueDialogViewModel
import indi.renakoni.nextvol.ui.home.settings.about.settingsAboutDestination
import indi.renakoni.nextvol.ui.home.settings.debug.settingsDebugDestination
import indi.renakoni.nextvol.ui.home.settings.formats.settingsFormatsDestination
import indi.renakoni.nextvol.ui.home.settings.licenses.settingsLicensesDestination
import indi.renakoni.nextvol.ui.home.settings.logcat.navigateToSettingsLogcatDestination
import indi.renakoni.nextvol.ui.home.settings.logcat.settingsLogcatDestination
import indi.renakoni.nextvol.ui.home.settings.pluginmanager.navigateToSettingsPluginManagerHomeDestination
import indi.renakoni.nextvol.ui.home.settings.pluginmanager.settingsPluginManagerNavigation
import indi.renakoni.nextvol.ui.home.settings.sources.settingsSourcesDestination
import indi.renakoni.nextvol.ui.home.settings.sources.sourceDiagnosticDestination
import indi.renakoni.nextvol.ui.home.settings.textformatting.editTextFormattingRuleDialog
import indi.renakoni.nextvol.ui.home.settings.textformatting.navigateToSettingsTextFormattingManagerDestination
import indi.renakoni.nextvol.ui.home.settings.textformatting.settingsTextFormattingNavigation
import indi.renakoni.nextvol.ui.home.settings.theme.navigateToSettingsThemeDestination
import indi.renakoni.nextvol.ui.home.settings.theme.settingsThemeDestination
import indi.renakoni.nextvol.ui.home.settings.updates.settingsUpdatesDestination
import indi.renakoni.nextvol.ui.storagemanager.navigateToStorageManager
import indi.renakoni.nextvol.ui.tts.navigateToSpeechSettings
import indi.renakoni.nextvol.ui.tts.speechSettingsDestination
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import indi.renakoni.nextvol.utils.uriLauncher
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.settingsDestination() {
    composable<Route.Main.Settings.Home> {
        val navController = LocalNavController.current
        val settingsViewModel = hiltViewModel<SettingsViewModel>()
        SettingsScreen(
            settingState = settingsViewModel.settingState,
            importData = settingsViewModel::importFromFile,
            onClickUpdates = { if (navController.isResumed()) navController.navigate(Route.Main.Settings.Updates) },
            onClickAbout = { if (navController.isResumed()) navController.navigate(Route.Main.Settings.About) },
            onClickChangeSource = { navController.navigate(Route.Main.Settings.Sources) },
            onClickExportUserData = navController::navigateToExportUserDataDialog,
            onClickLogcat = navController::navigateToSettingsLogcatDestination,
            onClickTextFormatting = navController::navigateToSettingsTextFormattingManagerDestination,
            onClickReadAloud = navController::navigateToSpeechSettings,
            onClickPluginManager = navController::navigateToSettingsPluginManagerHomeDestination,
            onClickBangumi = { navController.navigate(indi.renakoni.nextvol.ui.bangumi.BangumiRoute()) { launchSingleTop = true } },
            onClickThemeSettings = navController::navigateToSettingsThemeDestination,
            onClickStorageManager = navController::navigateToStorageManager,
            onBack = { navController.popBackStackIfResumed() }
        )
    }
    settingsSourcesDestination()
    sourceDiagnosticDestination()
    exportUserDataDialog()
    editTextFormattingRuleDialog()
    sliderValueDialog()
}

@OptIn(ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.settingsNavigation() {
    navigation<Route.Main.Settings>(
        startDestination = Route.Main.Settings.Home
    ) {
        settingsDestination()
        settingsDebugDestination()
        settingsLogcatDestination()
        settingsUpdatesDestination()
        settingsAboutDestination()
        settingsThemeDestination()
        settingsTextFormattingNavigation()
        settingsPluginManagerNavigation()
        settingsLicensesDestination()
        settingsFormatsDestination()
        speechSettingsDestination()
    }
}

@Suppress("unused")
fun NavController.navigateToSettingsDestination() {
    if (!isResumed() || currentDestination?.hierarchy?.any { it.hasRoute<Route.Main.Settings>() } == true) return
    navigate(Route.Main.Settings) { launchSingleTop = true }
}

private fun NavGraphBuilder.sliderValueDialog() {
    dialog<Route.SliderValueDialog> { entry ->
        val navController = LocalNavController.current
        val viewModel = hiltViewModel<SliderValueDialogViewModel>()
        val route = entry.toRoute<Route.SliderValueDialog>()
        val value = route.value
        SliderValueDialog(
            value = value,
            onValueChange = { viewModel.setValue(it) },
            onDismissRequest = { navController.popBackStack() },
            onConfirmation = {
                navController.popBackStack()
            }
        )

    }
}

fun NavController.navigateToSliderValueDialog(path: String, value: Float) {
    if (!this.isResumed()) return
    navigate(Route.SliderValueDialog(value, path))
}


private fun NavGraphBuilder.exportUserDataDialog() {
    dialog<Route.Main.ExportUserDataDialog> {
        val navController = LocalNavController.current
        val context = LocalContext.current
        val workManager = WorkManager.getInstance(context)
        val viewModel = hiltViewModel<ExportUserDataDialogViewModel>()
        var exportContext: ExportContext by remember { mutableStateOf(MutableExportContext()) }
        val saveDataToFileLauncher = uriLauncher { uri ->
            CoroutineScope(Dispatchers.Main).launch {
                workManager.getWorkInfoByIdFlow(viewModel.exportToFile(uri, exportContext).id).collect {
                    when (it?.state) {
                        WorkInfo.State.FAILED -> {
                            Toast.makeText(context.applicationContext, R.string.data_export_failed, Toast.LENGTH_SHORT).show()
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            Toast.makeText(context.applicationContext, R.string.data_export_success, Toast.LENGTH_SHORT).show()
                        }
                        else -> {}
                    }
                }
            }
            navController.popBackStack()
        }
        val selectLocationTitle = stringResource(R.string.select_location)
        ExportUserDataDialog(
            onDismissRequest = { navController.popBackStack() },
            onClickSaveAndSend = {
                viewModel.exportAndSendToFile(exportContext, context) {
                    navController.popBackStack()
                }
            },
            onClickSaveToFile = {
                exportContext = it
                createDataFile("NextVolData", selectLocationTitle, saveDataToFileLauncher)
            }
        )
    }
}

private fun NavController.navigateToExportUserDataDialog() {
    navigate(Route.Main.ExportUserDataDialog)
}

@Suppress("DuplicatedCode", "SameParameterValue")
private fun createDataFile(fileName: String, chooserTitle: String, launcher: ManagedActivityResultLauncher<Intent, ActivityResult>) {
    val initUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initUri)
        putExtra(Intent.EXTRA_TITLE, "$fileName.lnr")
    }
    launcher.launch(Intent.createChooser(intent, chooserTitle))
}
