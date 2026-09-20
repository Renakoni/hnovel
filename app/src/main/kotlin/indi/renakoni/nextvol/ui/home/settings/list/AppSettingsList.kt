package indi.renakoni.nextvol.ui.home.settings.list

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry

@Composable
fun AppSettingsList(
    onClickLogcat: () -> Unit,
    onClickUpdates: () -> Unit,
    onClickAbout: () -> Unit,
) {
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.bug_report_24px),
        title = stringResource(R.string.settings_app_logs),
        description = stringResource(R.string.settings_app_logs_desc),
        onClick = onClickLogcat
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.deployed_code_update_24px),
        title = stringResource(R.string.app_updates),
        description = stringResource(R.string.settings_updates_desc),
        onClick = onClickUpdates
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.info_24px),
        title = stringResource(R.string.about_settings),
        description = stringResource(R.string.settings_about_desc),
        onClick = onClickAbout
    )
}
