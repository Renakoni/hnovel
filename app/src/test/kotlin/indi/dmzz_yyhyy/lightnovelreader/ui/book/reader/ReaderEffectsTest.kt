package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderEffectsTest {
    @Test
    fun nonImmersiveModeShowsAllSystemBars() {
        assertEquals(
            ReaderSystemBarMode.Visible,
            readerSystemBarMode(immersive = false, enableHideStatusBar = true)
        )
    }

    @Test
    fun immersiveModeWithHiddenStatusBarHidesAllSystemBars() {
        assertEquals(
            ReaderSystemBarMode.Hidden,
            readerSystemBarMode(immersive = true, enableHideStatusBar = true)
        )
    }

    @Test
    fun immersiveModeWithVisibleStatusBarHidesOnlyNavigationBar() {
        assertEquals(
            ReaderSystemBarMode.StatusBarOnly,
            readerSystemBarMode(immersive = true, enableHideStatusBar = false)
        )
    }
}
