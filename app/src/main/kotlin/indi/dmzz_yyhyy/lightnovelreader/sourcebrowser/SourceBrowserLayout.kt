package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.content.Context
import android.view.View
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.Toolbar
import indi.dmzz_yyhyy.lightnovelreader.R
import kotlin.math.roundToInt

/** Window chrome only; each browser retains ownership of navigation and completion. */
internal fun sourceBrowserLayout(
    context: Context,
    title: String,
    browser: View,
    closeLabel: CharSequence,
    onClose: () -> Unit,
    confirmLabel: CharSequence? = null,
    onConfirm: () -> Unit = {},
): LinearLayout = LinearLayout(context).apply {
    fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    orientation = LinearLayout.VERTICAL
    fitsSystemWindows = true
    setBackgroundColor(context.getColor(R.color.source_browser_background))
    addView(Toolbar(context).apply {
        this.title = title.ifBlank { context.getString(R.string.source_browser_title) }
        setTitleTextColor(context.getColor(R.color.source_browser_foreground))
        navigationIcon = context.getDrawable(R.drawable.close_24px)?.mutate()?.apply {
            setTint(context.getColor(R.color.source_browser_foreground))
        }
        navigationContentDescription = closeLabel
        setNavigationOnClickListener { onClose() }
        contentInsetStartWithNavigation = 0
        contentInsetEndWithActions = 0
        if (confirmLabel != null) {
            menu.add(0, android.R.id.button1, 0, confirmLabel).apply {
                icon = context.getDrawable(R.drawable.check_24px)?.mutate()?.apply {
                    setTint(context.getColor(R.color.source_browser_accent))
                }
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { onConfirm(); true }
            }
        }
    }, LinearLayout.LayoutParams(-1, dp(56)))
    addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
}
