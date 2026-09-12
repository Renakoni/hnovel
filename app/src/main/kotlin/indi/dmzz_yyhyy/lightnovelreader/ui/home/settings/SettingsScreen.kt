package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings

import android.net.Uri
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequest
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.components.SectionHeader
import indi.dmzz_yyhyy.lightnovelreader.ui.components.SettingsClickableEntry
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list.AboutSettingsList
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list.AppSettingsList
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list.DataSettingsList
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list.DisplaySettingsList
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list.ExtensionsSettingsList
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list.ReadingSettingsList
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list.UpdatesSettingsList
import indi.dmzz_yyhyy.lightnovelreader.utils.navigationBarSpacer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(
    settingState: SettingState,
    updatePhase: String,
    checkUpdate: () -> Unit,
    importData: (Uri, Boolean) -> OneTimeWorkRequest,
    onClickLogcat: () -> Unit,
    onClickChangeSource: () -> Unit,
    onClickExportUserData: () -> Unit,
    onClickDebugMode: () -> Unit,
    onClickLicenses: () -> Unit,
    onClickThemeSettings: () -> Unit,
    onClickPluginManager: () -> Unit,
    onClickTextFormatting: () -> Unit,
    onClickStorageManager: () -> Unit,
    clearReadingCache: suspend () -> Unit,
    onOptOut: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()

    Column {
        SettingsTopBar(scrollBehavior, onBack)
        LazyColumn(
            Modifier.fillMaxSize(), listState
        ) {
            item {
                SettingsCategory(
                    title = stringResource(R.string.extensions_settings),
                ) {
                    ExtensionsSettingsList(
                        onClickChangeSource = onClickChangeSource,
                        onClickPluginManager = onClickPluginManager
                    )
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.reading_settings),
                ) {
                    ReadingSettingsList(
                        settingState = settingState,
                        onClickTheme = onClickThemeSettings,
                        onClickTextFormatting = onClickTextFormatting
                    )
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.display_settings),
                ) {
                    DisplaySettingsList()
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.app_updates)
                ) {
                    UpdatesSettingsList(
                        updatePhase = updatePhase,
                        settingState = settingState,
                        checkUpdate = checkUpdate,
                    )
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.data_settings),
                ) {
                    DataSettingsList(
                        onClickExportUserData = onClickExportUserData,
                        settingState = settingState,
                        importData = importData,
                        onClickStorageManager = onClickStorageManager,
                        clearReadingCache = clearReadingCache
                    )
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.app_settings),
                ) {
                    AppSettingsList(
                        settingState = settingState,
                        onClickLogcat = onClickLogcat,
                    )
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.about_settings),
                ) {
                    AboutSettingsList(
                        settingState = settingState,
                        onClickLicenses = onClickLicenses,
                        onOptOut = onOptOut
                    )
                }
            }
            if (BuildConfig.DEBUG) {
                item {
                    SettingsCategory(
                        title = stringResource(R.string.debug_settings)
                    ) {
                        SettingsClickableEntry(
                            modifier = Modifier.background(colorScheme.surfaceContainer),
                            painter = painterResource(R.drawable.adb_24px),
                            title = stringResource(R.string.settings_debug_tools),
                            description = stringResource(R.string.settings_debug_tools_desc),
                            onClick = onClickDebugMode
                        )
                    }
                }
            }
            navigationBarSpacer()
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.nav_settings), style = typography.displayLarge
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(id = R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.sources_back)
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun SettingsCategory(
    title: String? = null, content: @Composable ColumnScope.() -> Unit
) {
    title?.let {
        SectionHeader(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp), text = it
        )
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
}
