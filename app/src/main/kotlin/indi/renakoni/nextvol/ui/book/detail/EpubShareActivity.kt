package indi.renakoni.nextvol.ui.book.detail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.work.EpubShareFiles
import java.util.UUID

/** Both foreground completion and notification taps use an Activity to open the chooser. */
class EpubShareActivity : Activity() {
    companion object {
        fun intent(context: Context, id: UUID, automatic: Boolean = false) =
            Intent(context, EpubShareActivity::class.java)
                .setData(android.net.Uri.parse("nextvol-epub:$id"))
                .putExtra("automatic", automatic)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            try {
                val id = UUID.fromString(intent.data?.schemeSpecificPart)
                if (!intent.getBooleanExtra("automatic", false) || !EpubShareFiles.wasShared(this, id)) {
                    startActivity(EpubShareFiles.chooser(this, id))
                    EpubShareFiles.markShared(this, id)
                }
            } catch (_: RuntimeException) {
                Toast.makeText(this, R.string.epub_export_share_failed, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
