package indi.renakoni.nextvol.benchmark.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import indi.renakoni.nextvol.benchmark.ui.UiAutomatorTest
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BookAndReaderTest : UiAutomatorTest() {
    private fun openBookDetails() {
        launchApp()
        openBottomNavigation("Bookshelf")
        clickText("Benchmark Sample Novel")
        assertText("Benchmark Sample Novel")
    }

    @Test
    fun detailActionsOpenExportFormattingAndMoreMenus() {
        openBookDetails()

        clickDescription("Export")
        assertText("Export as Epub")
        pressBack()

        clickDescription("Text Formatting")
        assertText("Book Rules")
        pressBack()

        clickDescription("More options")
        assertText("Mark as read…")
        pressBack()
    }

    @Test
    fun chapterSelectionAndReaderControlsWork() {
        openBookDetails()
        clickText("Benchmark Chapter One")
        assertTextContains("Benchmark paragraph")

        device.click(device.displayWidth / 2, device.displayHeight / 2)
        assertDescription("Contents")
        assertDescription("Settings")
        assertDescription("Bookmark")

        clickDescription("Contents")
        assertText("Select Chapter")
        assertText("Benchmark Chapter One")
        clickCenter(scrollToText("Benchmark Bonus Volume"))
        clickCenter(scrollToText("Benchmark Chapter Two"))
        assertForegroundPackage(TARGET_PACKAGE)
    }

    @Test
    fun readerSettingsExposeAllGroupsAndPageModes() {
        openBookDetails()
        clickText("Benchmark Chapter One")
        assertTextContains("Benchmark paragraph")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickDescription("Settings")

        assertText("Reader Settings")
        assertText("Appearance")
        assertText("Controls")
        assertText("Layout")
        assertText("Keep Screen On")
        assertText("Hide Status Bar")
        assertText("Theme Settings…")

        clickText("Controls")
        assertText("Page Turn Mode")
        assertText("Back Prevention")
    }

    @Test
    fun scrollingAndVolumeNavigationKeepReaderResponsive() {
        openBookDetails()
        clickText("Benchmark Chapter One")
        assertTextContains("Benchmark paragraph")

        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.75).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.25).toInt(),
            30,
        )
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP)
        assertTextContains("Benchmark paragraph")
    }

    @Test
    fun bookInformationSheetShowsEveryMetadataGroup() {
        openBookDetails()
        clickScrolledText("Info")

        assertText("Title")
        assertText("Benchmark Sample Novel")
        assertText("ID")
        assertText("9999999")
        assertText("Author")
        assertText("Benchmark Author")
        scrollToText("Stats")
        assertTextContains("12")
        assertTextContains("2 chapters")
    }

    @Test
    fun epubExportOptionsSupportVolumeAndSelectionBranches() {
        openBookDetails()
        clickDescription("Export")

        assertText("Export as Epub")
        assertText("Include images")
        assertText("Export by volumes")
        clickText("Export by volumes")
        assertText("Benchmark Volume")
        assertText("Benchmark Bonus Volume")
        clickText("Benchmark Volume")
        assertText("Select All")
        clickText("Select All")
        assertTextNotVisible("Select All")
    }

    @Test
    fun markReadDialogSupportsRangeSelectionAndConfirmation() {
        openBookDetails()
        clickDescription("More options")
        clickText("Mark as read…")

        assertText("All chapters")
        assertText("Choose range")
        clickText("Choose range")
        assertText("Benchmark Chapter One")
        assertText("Benchmark Chapter Two")
        clickText("Benchmark Chapter One")
        clickText("Benchmark Chapter Two")
        assertText("Mark 2 chapters as read")
        clickText("Mark 2 chapters as read")

        assertText("Benchmark Sample Novel")
        device.waitForIdle(2_000)
        restartApp()
        openBottomNavigation("Bookshelf")
        clickText("Benchmark Sample Novel")
        scrollToText("Finished Reading")
    }

    @Test
    fun markReadDialogCanBeCancelledWithoutChangingProgress() {
        openBookDetails()
        clickDescription("More options")
        clickText("Mark as read…")
        clickText("Cancel")

        assertText("Benchmark Sample Novel")
        clickDescription("More options")
        clickText("Mark as read…")
        assertText("Mark all as read")
    }

    @Test
    fun readerModeSwitchesExposeConditionalControlsAndMargins() {
        openBookDetails()
        clickText("Benchmark Chapter One")
        assertTextContains("Benchmark paragraph")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickDescription("Settings")

        clickText("Controls")
        assertText("Page Turn Mode")
        scrollToText("Continuous Scrolling")
        device.findObjects(By.scrollable(true))
            .maxByOrNull { it.visibleBounds.height() }
            ?.scroll(Direction.DOWN, 1f)
        device.waitForIdle()
        clickText("Page Turn Mode")
        assertText("Volume Key Navigation")
        scrollToText("Tap to Turn Pages")
        scrollToText("Page Turn Animation")
        scrollToText("Quick Chapter Switch")

        clickText("Layout")
        clickScrolledText("Auto Margin Adjustment")
        scrollToText("Top Margin")
        scrollToText("Bottom Margin")
        scrollToText("Left Margin")
        scrollToText("Right Margin")
    }

    @Test
    fun readerAppearanceTogglesPersistAcrossReaderReentry() {
        openBookDetails()
        clickText("Benchmark Chapter One")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickDescription("Settings")
        assertText("Keep Screen On")
        clickText("Keep Screen On")
        assertFirstSwitchChecked(true)

        pressBack()
        pressBack()
        clickText("Benchmark Chapter One")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickDescription("Settings")
        assertFirstSwitchChecked(true)
    }
}
