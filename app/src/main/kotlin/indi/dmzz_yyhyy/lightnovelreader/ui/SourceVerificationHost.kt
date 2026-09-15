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
import indi.dmzz_yyhyy.lightnovelreader.data.web.sourceFailureMessage
import hnovel.content.SourceContentException
import hnovel.network.BrowserChallengeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Foreground verification is owned by its request. Only background notices need an action. */
@Composable
fun SourceVerificationHost(coordinator: SourceVerificationCoordinator) {
    LaunchedEffect(coordinator) { coordinator.observeRetirement() }
    val prompts by coordinator.prompts.collectAsStateWithLifecycle()
    val foreground = prompts.firstOrNull { it.foreground }
    val background = prompts.firstOrNull { !it.foreground }
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<Int?>(null) }
    if (foreground == null && background != null && !background.opening) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(action = {
                TextButton(onClick = {
                    scope.launch {
                        try { failure = null; coordinator.verifyBackground(background.id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: SourceContentException) { failure = sourceFailureMessage(error) }
                        catch (_: Exception) { failure = R.string.source_verification_failed }
                    }
                }) { Text(stringResource(if (background.kind == BrowserChallengeKind.Login) R.string.sources_login_continue
                    else R.string.source_verification_background_open)) }
            }, dismissAction = {
                TextButton(onClick = { coordinator.dismiss(background.id) }) { Text(stringResource(android.R.string.cancel)) }
            }) { Text(stringResource(if (background.kind == BrowserChallengeKind.Login) R.string.source_login_background
                else R.string.source_verification_background, background.name)) }
        }
    }
    failure?.let { message -> AlertDialog(onDismissRequest = { failure = null },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(android.R.string.ok)) } }) }
}
