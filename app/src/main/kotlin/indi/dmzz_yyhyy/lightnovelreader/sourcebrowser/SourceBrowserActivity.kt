package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import indi.dmzz_yyhyy.lightnovelreader.R

/** The only foreground window is opened by an explicit source-login action. */
class SourceBrowserActivity : Activity() {
    private var browser: SourceBrowserService? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = SourceBrowserService.active ?: run { finish(); return }
        browser = active; active.activity = this
        val view = active.webView ?: run { finish(); return }
        (view.parent as? ViewGroup)?.removeView(view)
        setContentView(sourceBrowserLayout(this, active.pageTitle, view,
            getText(if (active.verificationCode) android.R.string.cancel else R.string.source_browser_done),
            { if (active.verificationCode) active.fail() else active.evaluate() },
            if (active.verificationCode) getText(android.R.string.ok) else null, { active.evaluate() }))
    }
    @Deprecated("Platform callback") override fun onBackPressed() {
        val active = browser ?: run { super.onBackPressed(); return }
        if (active.verificationCode) active.fail()
        else if (active.webView?.canGoBack() == true) active.webView?.goBack()
        else active.evaluate()
    }
    override fun onDestroy() {
        if (browser?.activity === this) browser?.activity = null
        if (isFinishing) browser?.fail()
        super.onDestroy()
    }
}
