package indi.renakoni.nextvol.ui.home.settings.list

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.home.settings.SettingState
import indi.renakoni.nextvol.ui.home.settings.data.MenuOptions
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.showSnackbar

@Composable
fun AppSettingsList(
    settingState: SettingState,
    onClickLogcat: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHost.current

    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.bug_report_24px),
        title = stringResource(R.string.settings_app_logs),
        description = stringResource(R.string.settings_app_logs_desc),
        onClick = onClickLogcat
    )
    val restartToApplyText = stringResource(R.string.restart_to_apply_changes)
    SettingsMenuEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.bug_report_24px),
        title = stringResource(R.string.settings_app_log_level),
        description = stringResource(R.string.settings_app_log_level_desc),
        options = MenuOptions.LogLevelOptions,
        selectedOptionKey = settingState.logLevelKey,
        onOptionChange = { option ->
            settingState.logLevelKeyUserData.asynchronousSet(option)
            showSnackbar(
                coroutineScope = coroutineScope,
                hostState = snackbarHostState,
                message = restartToApplyText
            ) { }
        }
    )
}