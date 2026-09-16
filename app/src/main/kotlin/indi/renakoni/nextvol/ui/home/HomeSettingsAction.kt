package indi.renakoni.nextvol.ui.home

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R

/** Shared action, placed last in each root's existing app bar. */
@Composable
internal fun HomeSettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.outline_settings_24px), stringResource(R.string.nav_settings))
    }
}
