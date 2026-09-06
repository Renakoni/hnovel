package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.work.ImportDataWork
import indi.dmzz_yyhyy.lightnovelreader.utils.analytics.MatomoAnalytics
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    userDataRepository: UserDataRepository,
    private val workManager: WorkManager,
    private val matomoAnalytics: MatomoAnalytics,
) : ViewModel() {
    var settingState: SettingState = SettingState(userDataRepository, viewModelScope)

    fun trackOptOut() = matomoAnalytics.trackOptOut()

    fun importFromFile(
        uri: Uri,
        overwrite: Boolean = false,
        ignoreDataIdCheck: Boolean = false
    ): OneTimeWorkRequest {
        val workRequest = OneTimeWorkRequestBuilder<ImportDataWork>()
            .setInputData(
                workDataOf(
                    "uri" to uri.toString(),
                    "overwrite" to overwrite,
                    "ignoreDataIdCheck" to ignoreDataIdCheck
                )
            )
            .build()
        workManager.enqueueUniqueWork(
            uri.toString(),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        return workRequest
    }
}