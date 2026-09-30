package indi.renakoni.nextvol.reader

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Isolated host for reader layout instrumentation; never included in release builds. */
class ReaderLayoutTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installReader?.invoke(this)
    }

    companion object {
        // Reinstall the test composition after a real Activity.recreate, using its retained VM.
        var installReader: ((ReaderLayoutTestActivity) -> Unit)? = null
    }
}
