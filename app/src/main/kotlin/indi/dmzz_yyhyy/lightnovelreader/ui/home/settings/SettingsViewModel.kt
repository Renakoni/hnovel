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
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    userDataRepository: UserDataRepository,
    private val workManager: WorkManager,
    private val matomoAnalytics: MatomoAnalytics,
    @ApplicationContext private val context: Context,
    private val database: LightNovelReaderDatabase,
) : ViewModel() {
    var settingState: SettingState = SettingState(userDataRepository, viewModelScope)

    fun trackOptOut() = matomoAnalytics.trackOptOut()

    /** Explicit reading-cache removal. Metadata, progress and login state are separate stores. */
    suspend fun clearReadingCache(): Unit = withContext(Dispatchers.IO) {
        database.chapterContentDao().clear()
        val loader = coil3.SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

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
