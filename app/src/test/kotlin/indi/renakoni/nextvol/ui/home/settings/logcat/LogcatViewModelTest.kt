package indi.renakoni.nextvol.ui.home.settings.logcat

import indi.renakoni.nextvol.data.logging.LoggerRepository
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatViewModelTest {
    @Test fun selectingLiveLogsDoesNotTreatTheOptionAsAFile() {
        val repository = mockk<LoggerRepository>(relaxed = true)
        val model = LogcatViewModel(repository)
        val archive = "lnr_export_20260919_100000.log"
        model.onSelectLogFile(archive)
        assertTrue(model.uiState.isFileMode)
        assertEquals(archive, model.uiState.selectedLogFile)
        verify(exactly = 1) { repository.loadLogFile(archive) }

        model.onSelectLogFile(LIVE_LOG_OPTION)
        assertFalse(model.uiState.isFileMode)
        assertEquals(LIVE_LOG_OPTION, model.uiState.selectedLogFile)
        verify(exactly = 0) { repository.loadLogFile(LIVE_LOG_OPTION) }
    }

    @Test fun deletingAnArchiveReturnsToLiveLogsAndSharesTheLiveStream() {
        val repository = mockk<LoggerRepository>(relaxed = true)
        val model = LogcatViewModel(repository)
        val archive = "lnr_panic_20260919_100000.log"
        model.onSelectLogFile(archive)
        model.deleteLogFile(archive)
        model.shareLogs()

        assertFalse(model.uiState.isFileMode)
        assertEquals(LIVE_LOG_OPTION, model.uiState.selectedLogFile)
        verify(exactly = 1) { repository.deleteLogFile(archive) }
        verify(exactly = 1) { repository.shareLogs(null) }
        verify(exactly = 0) { repository.loadLogFile(LIVE_LOG_OPTION) }
    }
}
