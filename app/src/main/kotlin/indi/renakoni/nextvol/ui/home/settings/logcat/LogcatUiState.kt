package indi.renakoni.nextvol.ui.home.settings.logcat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import indi.renakoni.nextvol.data.logging.LogEntry

internal const val LIVE_LOG_OPTION = ""

@Stable
interface LogcatUiState {
    val isFileMode: Boolean
    val selectedLogFile: String
    val displayedLogEntries: List<LogEntry>
}

class MutableLogcatUiState : LogcatUiState {
    override var isFileMode: Boolean by mutableStateOf(false)
    override var selectedLogFile: String by mutableStateOf(LIVE_LOG_OPTION)
    override var displayedLogEntries: List<LogEntry> by mutableStateOf(emptyList())
}
