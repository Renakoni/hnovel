package indi.renakoni.nextvol.sourcebrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import indi.renakoni.nextvol.R

/** The only foreground window is opened by an explicit source-login action. */
class SourceBrowserActivity : Activity() {
    private var browser: SourceBrowserService? = null
    private var systemBack: SourceBrowserBack? = null
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
        systemBack = SourceBrowserBack(this) { handleBack() }.also { it.register() }
    }
    private fun handleBack() {
        val active = browser ?: run { super.onBackPressed(); return }
        if (active.verificationCode) active.fail()
        else if (active.webView?.canGoBack() == true) active.webView?.goBack()
        else active.evaluate()
    }
    // API 24-32 fallback; API 33+ uses SourceBrowserBack's registered callback.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleBack()
    override fun onDestroy() {
        systemBack?.unregister()
        if (browser?.activity === this) {
            browser?.activity = null
            if (isFinishing) browser?.fail()
        }
        super.onDestroy()
    }
}
