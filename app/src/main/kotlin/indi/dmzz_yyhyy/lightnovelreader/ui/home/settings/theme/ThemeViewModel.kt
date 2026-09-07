package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderSettingsEditor
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ThemeSettingsEditor
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    userDataRepository: UserDataRepository,
) : ViewModel() {
    private val settingState = SettingState(
        userDataRepository,
        viewModelScope
    )
    val themeSettings: ThemeSettingsEditor = settingState
    val readerSettings: ReaderSettingsEditor = settingState
}
