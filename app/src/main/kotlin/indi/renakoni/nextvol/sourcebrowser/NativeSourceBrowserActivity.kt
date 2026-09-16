package indi.renakoni.nextvol.sourcebrowser

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import indi.renakoni.nextvol.R

/** User-controlled website window; completion returns the current document to its waiting request. */
class NativeSourceBrowserActivity : Activity() {
    private var browser: NativeSourceBrowserService? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = NativeSourceBrowserService.active ?: run { finish(); return }
        val view = active.webView ?: run { finish(); return }
        browser = active; active.activity = this
        (view.parent as? ViewGroup)?.removeView(view)
        setContentView(sourceBrowserLayout(this, active.title, view,
            getText(android.R.string.cancel), { active.cancel() },
            getText(R.string.source_browser_done), { active.confirm() }))
    }
    @Deprecated("Platform callback") override fun onBackPressed() {
        if (browser?.webView?.canGoBack() == true) browser?.webView?.goBack() else browser?.cancel()
    }
    override fun onDestroy() {
        if (browser?.activity === this) {
            browser?.activity = null
            if (isFinishing) browser?.cancel()
        }
        super.onDestroy()
    }
}
