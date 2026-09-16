package indi.renakoni.nextvol.ui.home.settings.formats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.home.settings.SettingState
import javax.inject.Inject

@HiltViewModel
class FormatsViewModel @Inject constructor(
    userDataRepository: UserDataRepository,
) : ViewModel() {
    val settingState = SettingState(
        userDataRepository,
        viewModelScope
    )
}