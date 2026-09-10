package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

/** An actual foreground owner for browser instrumentation, without unrelated onboarding dialogs. */
class BrowserTestHostActivity : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.TextView(this).apply { text = "Browser test host" })
    }
}
