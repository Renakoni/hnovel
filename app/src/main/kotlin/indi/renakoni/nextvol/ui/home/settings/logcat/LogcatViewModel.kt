package indi.renakoni.nextvol.ui.home.settings.logcat

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.logging.LogEntry
import indi.renakoni.nextvol.data.logging.LoggerRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import javax.inject.Inject

@HiltViewModel
class LogcatViewModel @Inject constructor (
    private val loggerRepository: LoggerRepository,
    userDataRepository: UserDataRepository,
): ViewModel() {
    val logLevelUserData = userDataRepository.stringUserData(UserDataPath.Settings.Data.LogLevel.path)

    private val _uiState = MutableLogcatUiState()
    val uiState: LogcatUiState = _uiState

    fun startLogging() {
        loggerRepository.startLogging()
        _uiState.isFileMode = false
        _uiState.displayedLogEntries = loggerRepository.realTimeLogEntries
        Log.i("Logger", "----- history")
    }

    fun clearLogs() = loggerRepository.refreshLogs()

    fun shareLogs() {
        if (_uiState.isFileMode) {
            loggerRepository.shareLogs(_uiState.selectedLogFile)
        } else {
            loggerRepository.shareLogs()
        }
    }

    val displayedLogEntries: List<LogEntry>
        get() = if (_uiState.isFileMode) {
            loggerRepository.fileLogEntries
        } else {
            loggerRepository.realTimeLogEntries
        }

    fun deleteLogFile(fileName: String) {
        loggerRepository.deleteLogFile(fileName)
        onSelectLogFile(LIVE_LOG_OPTION)
    }

    fun onSelectLogFile(fileName: String) {
        _uiState.isFileMode = fileName != LIVE_LOG_OPTION
        _uiState.selectedLogFile = fileName
        if (_uiState.isFileMode) loggerRepository.loadLogFile(fileName)
    }

    val logFilenameList: List<String>
        get() = loggerRepository.getAvailableLogFiles() + LIVE_LOG_OPTION
}
