package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
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
            addView(LinearLayout(this@SourceBrowserActivity).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(ImageButton(context).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    contentDescription = getString(R.string.source_browser_done)
                    setOnClickListener { if (active.verificationCode) active.fail() else active.evaluate() }
                })
                addView(android.widget.TextView(context).apply { text = active.pageTitle }, LinearLayout.LayoutParams(0, -2, 1f))
                if (active.verificationCode) addView(Button(context).apply {
                    setText(android.R.string.ok); setOnClickListener { active.evaluate() }
                })
            })
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        })
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
