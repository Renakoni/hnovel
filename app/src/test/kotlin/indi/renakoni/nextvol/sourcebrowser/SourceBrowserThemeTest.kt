package indi.renakoni.nextvol.sourcebrowser

import android.app.Application
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import indi.renakoni.nextvol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 27, 35], application = Application::class)
class SourceBrowserThemeTest {
    @Test @Config(qualifiers = "notnight")
    fun lightThemeKeepsNavigationIconsReadableAcrossApiLevels() = assertNavigation(night = false)

    @Test @Config(qualifiers = "night")
    fun darkThemeKeepsItsDarkNavigationBar() = assertNavigation(night = true)

    private fun assertNavigation(night: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_SourceBrowser)
        val color = TypedValue()
        assertTrue(context.theme.resolveAttribute(android.R.attr.navigationBarColor, color, true))
        assertEquals(if (!night && Build.VERSION.SDK_INT < 27) Color.BLACK
            else context.getColor(R.color.source_browser_background), color.data)
        if (Build.VERSION.SDK_INT >= 27) {
            val lightBar = TypedValue()
            assertTrue(context.theme.resolveAttribute(android.R.attr.windowLightNavigationBar, lightBar, true))
            assertEquals(!night, lightBar.data != 0)
        }
    }
}
