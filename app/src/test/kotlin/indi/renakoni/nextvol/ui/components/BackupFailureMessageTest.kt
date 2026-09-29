package indi.renakoni.nextvol.ui.components

import androidx.work.workDataOf
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.backup.BackupException
import indi.renakoni.nextvol.data.backup.BackupFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFailureMessageTest {
    @Test fun recognizedFailuresHaveSpecificMessagesAndIoKeepsItsFallback() {
        val expected = mapOf(
            BackupFailure.INVALID to R.string.backup_invalid,
            BackupFailure.UNSUPPORTED_VERSION to R.string.backup_unsupported_version,
            BackupFailure.TOO_LARGE to R.string.backup_too_large,
        )
        expected.forEach { (failure, message) ->
            assertEquals(message, backupFailureMessage(workDataOf(BackupException.ERROR_KEY to failure.name), R.string.data_import_failed))
        }
        assertEquals(R.string.data_import_failed, backupFailureMessage(null, R.string.data_import_failed))
        assertEquals(R.string.backup_export_failed, backupFailureMessage(workDataOf(BackupException.ERROR_KEY to "unknown"), R.string.backup_export_failed))
    }
}
