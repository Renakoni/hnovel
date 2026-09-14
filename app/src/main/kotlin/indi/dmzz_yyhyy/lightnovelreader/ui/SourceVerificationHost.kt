package indi.dmzz_yyhyy.lightnovelreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.SourceVerificationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Only a visible host and an explicit click can start verification. */
@Composable
fun SourceVerificationHost(coordinator: SourceVerificationCoordinator) {
    LaunchedEffect(coordinator) { coordinator.observeRetirement() }
    val prompts by coordinator.prompts.collectAsStateWithLifecycle()
    val foreground = prompts.firstOrNull { it.foreground }
    if (foreground != null && !foreground.opening) {
        AlertDialog(onDismissRequest = { coordinator.dismiss(foreground.id) },
            title = { Text(stringResource(R.string.source_verification_title)) },
            text = { Text(stringResource(R.string.source_verification_resume, foreground.name)) },
            confirmButton = { TextButton(onClick = { coordinator.approve(foreground.id) }) {
                Text(stringResource(R.string.source_verification_open))
            } },
            dismissButton = { TextButton(onClick = { coordinator.dismiss(foreground.id) }) {
                Text(stringResource(android.R.string.cancel))
            } })
    }
    val background = prompts.firstOrNull { !it.foreground }
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    if (foreground == null && background != null && !background.opening) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(action = {
                TextButton(onClick = {
                    scope.launch {
                        try { failed = false; coordinator.verifyBackground(background.id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                    }
                }) { Text(stringResource(R.string.source_verification_background_open)) }
            }, dismissAction = {
                TextButton(onClick = { coordinator.dismiss(background.id) }) { Text(stringResource(android.R.string.cancel)) }
            }) { Text(stringResource(R.string.source_verification_background, background.name)) }
        }
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false },
        text = { Text(stringResource(R.string.source_verification_failed)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(android.R.string.ok)) } })
}
