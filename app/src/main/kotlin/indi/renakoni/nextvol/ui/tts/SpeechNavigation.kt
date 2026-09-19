package indi.renakoni.nextvol.ui.tts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.serialization.Serializable

@Serializable
object SpeechSettingsRoute

@Serializable
object HttpSpeechSourcesRoute

fun NavController.navigateToSpeechSettings() = navigate(SpeechSettingsRoute) { launchSingleTop = true }

fun NavGraphBuilder.speechSettingsDestination() {
    composable<SpeechSettingsRoute> {
        val nav = LocalNavController.current
        val context = LocalContext.current
        val viewModel = hiltViewModel<SpeechSettingsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        val preview = stringResource(R.string.tts_preview_text)
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
        SpeechSettingsScreen(state, playback, nav::popBackStackIfResumed,
            viewModel::selectEngine, viewModel::selectVoice, viewModel::setRate, viewModel::setPitch,
            onPreview = { viewModel.controller.preview(preview) }, onCommand = viewModel.controller::command,
            onSystemSettings = {
                try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }, onRefresh = viewModel::refresh,
            onHttpSources = { nav.navigate(HttpSpeechSourcesRoute) { launchSingleTop = true } })
    }
    composable<HttpSpeechSourcesRoute> {
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<HttpSpeechSourcesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::preview) }
        HttpSpeechSourcesScreen(state, nav::popBackStackIfResumed,
            onImport = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onSelect = viewModel::select, onDelete = viewModel::delete, onSites = viewModel::sites,
            onConfirmImport = viewModel::confirmImport, onDismissImport = viewModel::dismissPreview, onEdit = viewModel::edit)
    }
}
