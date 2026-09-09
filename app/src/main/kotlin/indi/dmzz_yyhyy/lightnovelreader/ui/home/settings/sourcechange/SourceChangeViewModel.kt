package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sourcechange

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.utils.restart
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SourceChangeViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val userDataRepository: UserDataRepository,
    webBookDataSourceManager: WebBookDataSourceManager
) : ViewModel() {

    private val _uiState = MutableSourceChangeUiState().apply {
        currentSourceId = webBookDataSourceProvider.value.id
        webDataSourceItems = webBookDataSourceManager.webDataSourceItems
    }
    val uiState: SourceChangeUiState = _uiState
    fun changeWebSource(newWebDataSourceId: Identifier) {
        if (newWebDataSourceId == _uiState.currentSourceId || _uiState.isProcessing) return
        _uiState.isProcessing = true
        viewModelScope.launch {
            try {
                userDataRepository.stringUserData(UserDataPath.Settings.Data.WebDataSourceId.path)
                    .set(newWebDataSourceId.toString())
                _uiState.currentSourceId = newWebDataSourceId
                // Existing browsing screens still recreate their provider on restart.
                // The shared database and all books keep their own source identities.
                restart(appContext)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Log.e("SourceChangeViewModel", "Failed to change browsing source", failure)
                Toast.makeText(appContext, "Failed to change data source", Toast.LENGTH_LONG).show()
            } finally {
                _uiState.isProcessing = false
            }
        }
    }
}
