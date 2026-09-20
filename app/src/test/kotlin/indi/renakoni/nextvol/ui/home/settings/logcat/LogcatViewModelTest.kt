package indi.renakoni.nextvol.ui.home.settings.logcat

import indi.renakoni.nextvol.data.logging.LoggerRepository
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatViewModelTest {
    @Test fun opensTheLatestSavedLogAndKeepsItWhenThePageResumes() {
        val repository = mockk<LoggerRepository>(relaxed = true)
        val archive = "lnr_panic_20260920_120000.log"
        every { repository.getAvailableLogFiles() } returns listOf(archive)
        val model = LogcatViewModel(repository, mockk(relaxed = true))
        model.startLogging()
        assertEquals(archive, model.uiState.selectedLogFile)
        assertTrue(model.uiState.isFileMode)
        verify(exactly = 1) { repository.loadLogFile(archive) }
    }

    @Test fun partiallyFailedClearRefreshesTheListAndOpensTheRemainingArchive() {
        val repository = mockk<LoggerRepository>(relaxed = true)
        val latest = "lnr_panic_20260920_120000.log"
        val older = "lnr_export_20260919_120000.log"
        var files = listOf(latest, older)
        every { repository.getAvailableLogFiles() } answers { files }
        every { repository.deleteLogs() } answers { files = listOf(older); false }
        val model = LogcatViewModel(repository, mockk(relaxed = true))
        assertFalse(model.deleteLogs())
        assertEquals(listOf(older, LIVE_LOG_OPTION), model.logFilenameList)
        assertEquals(older, model.uiState.selectedLogFile)
        verify { repository.loadLogFile(older) }
    }

    @Test fun failedClearKeepsTheArchiveAvailableAndReportsFailure() {
        val repository = mockk<LoggerRepository>(relaxed = true)
        val archive = "lnr_panic_20260920_120000.log"
        every { repository.getAvailableLogFiles() } returns listOf(archive)
        every { repository.deleteLogs() } returns false
        val model = LogcatViewModel(repository, mockk(relaxed = true))
        assertFalse(model.deleteLogs())
        assertEquals(archive, model.uiState.selectedLogFile)
        assertEquals(listOf(archive, LIVE_LOG_OPTION), model.logFilenameList)
    }

    @Test fun selectingLiveLogsDoesNotTreatTheOptionAsAFile() {
        val repository = mockk<LoggerRepository>(relaxed = true)
        val model = LogcatViewModel(repository, mockk(relaxed = true))
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

    @Test fun clearingLogsReturnsToTheCurrentRun() {
        val repository = mockk<LoggerRepository>(relaxed = true)
        val model = LogcatViewModel(repository, mockk(relaxed = true))
        val archive = "lnr_panic_20260919_100000.log"
        model.onSelectLogFile(archive)
        model.deleteLogs()
        model.shareLogs()

        assertFalse(model.uiState.isFileMode)
        assertEquals(LIVE_LOG_OPTION, model.uiState.selectedLogFile)
        verify(exactly = 1) { repository.deleteLogs() }
        verify(exactly = 1) { repository.shareLogs(null) }
        verify(exactly = 0) { repository.loadLogFile(LIVE_LOG_OPTION) }
    }
}
