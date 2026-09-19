package indi.renakoni.nextvol.benchmark.work

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import java.io.File

/** Receives the actual chooser grant in the separate benchmark APK's UID. */
class BookshelfShareTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            check(intent.action == Intent.ACTION_SEND && intent.type == "application/zip")
            val uri = requireNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            check(uri.authority == "indi.renakoni.nextvol.provider")
            check(uri.lastPathSegment == "NextVolBookshelfData.lnr")
            val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { it.readBytes() }
            val pending = File(cacheDir, "$FILE_NAME.pending")
            pending.writeBytes(bytes)
            check(pending.renameTo(File(cacheDir, FILE_NAME)))
        } catch (failure: Exception) {
            File(cacheDir, ERROR_NAME).writeText(failure.toString())
        } finally {
            finish()
        }
    }

    companion object {
        const val FILE_NAME = "received-bookshelf.lnr"
        const val ERROR_NAME = "bookshelf-share-error.txt"
    }
}
