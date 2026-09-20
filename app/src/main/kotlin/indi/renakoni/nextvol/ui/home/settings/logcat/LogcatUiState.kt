package indi.renakoni.nextvol.ui.home.settings.logcat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal const val LIVE_LOG_OPTION = ""

@Stable
interface LogcatUiState {
    val isFileMode: Boolean
    val selectedLogFile: String
}

class MutableLogcatUiState : LogcatUiState {
    override var isFileMode: Boolean by mutableStateOf(false)
    override var selectedLogFile: String by mutableStateOf(LIVE_LOG_OPTION)
}
