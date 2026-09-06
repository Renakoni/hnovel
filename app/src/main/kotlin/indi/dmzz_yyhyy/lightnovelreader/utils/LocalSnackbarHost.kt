package indi.dmzz_yyhyy.lightnovelreader.utils

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

val LocalSnackbarHost = compositionLocalOf { SnackbarHostState() }

val LocalClaimSnackbarHost = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

private var snackbarJob: Job? = null

fun showSnackbar(
    coroutineScope: CoroutineScope? = null,
    hostState: SnackbarHostState,
    message: String? = null,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = SnackbarDuration.Short,
    result: (SnackbarResult) -> Unit = {}
) {
    snackbarJob?.cancel()

    if (message == null) {
        snackbarJob = null
        hostState.currentSnackbarData?.dismiss()
        return
    }

    val scope = coroutineScope ?: CoroutineScope(Dispatchers.Main)

    snackbarJob = scope.launch(Dispatchers.Main) {
        val res = hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = withDismissAction,
            duration = duration
        )
        result(res)
    }
}
