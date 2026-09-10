package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            if (active.pageTitle.isNotBlank()) addView(android.widget.TextView(context).apply { text = active.pageTitle })
            addView(LinearLayout(this@SourceBrowserActivity).apply {
                addView(Button(context).apply { setText(R.string.source_browser_done); setOnClickListener { active.evaluate() } })
                addView(Button(context).apply { setText(android.R.string.cancel); setOnClickListener { active.fail() } })
            })
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
    @Deprecated("Platform callback") override fun onBackPressed() { browser?.fail(); super.onBackPressed() }
    override fun onDestroy() {
        if (browser?.activity === this) browser?.activity = null
        if (isFinishing) browser?.fail()
        super.onDestroy()
    }
}
