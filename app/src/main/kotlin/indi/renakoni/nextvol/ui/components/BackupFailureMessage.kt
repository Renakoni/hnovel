package indi.renakoni.nextvol.ui.components

import androidx.annotation.StringRes
import androidx.work.Data
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.backup.BackupException
import indi.renakoni.nextvol.data.backup.BackupFailure

@StringRes
internal fun backupFailureMessage(data: Data?, @StringRes fallback: Int): Int =
    when (data?.getString(BackupException.ERROR_KEY)) {
        BackupFailure.INVALID.name -> R.string.backup_invalid
        BackupFailure.UNSUPPORTED_VERSION.name -> R.string.backup_unsupported_version
        BackupFailure.TOO_LARGE.name -> R.string.backup_too_large
        else -> fallback
    }
