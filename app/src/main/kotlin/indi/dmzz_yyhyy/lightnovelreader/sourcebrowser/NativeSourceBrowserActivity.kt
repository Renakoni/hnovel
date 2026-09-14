package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import indi.dmzz_yyhyy.lightnovelreader.R

/** User-controlled website window; completion returns the current document to its waiting request. */
class NativeSourceBrowserActivity : Activity() {
    private var browser: NativeSourceBrowserService? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = NativeSourceBrowserService.active ?: run { finish(); return }
        val view = active.webView ?: run { finish(); return }
        browser = active; active.activity = this
        (view.parent as? ViewGroup)?.removeView(view)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(LinearLayout(context).apply {
                addView(Button(context).apply { setText(android.R.string.cancel); setOnClickListener { active.cancel() } })
                addView(TextView(context).apply { text = active.title }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(context).apply { setText(R.string.source_browser_done); setOnClickListener { active.confirm() } })
            })
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        })
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
