package indi.renakoni.nextvol.benchmark.bookshelf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import indi.renakoni.nextvol.benchmark.ui.UiAutomatorTest
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BookshelfTest : UiAutomatorTest() {
    private fun openBookshelf() {
        launchApp()
        openBottomNavigation("Bookshelf")
    }

    private fun openBookshelfMenu() {
        clickDescription("More options")
    }

    @Test
    fun seededShelfAndBookDetailsOpen() {
        launchApp()
        openBottomNavigation("Bookshelf")
        assertText("Benchmark Shelf")
        clickText("Benchmark Sample Novel")

        assertText("Benchmark Sample Novel")
        assertText("Benchmark Author")
        assertText("Benchmark Volume")
        assertDescription("Export")
        assertDescription("Text Formatting")
        assertDescription("More options")
    }

    @Test
    fun createBookshelfFlowPersistsNewShelf() {
        openBookshelf()
        openBookshelfMenu()
        clickText("Create Bookshelf")
        assertText("New Bookshelf")
        setFirstTextField("Automation Shelf")
        clickDescription("Save")
        assertText("Automation Shelf")

        restartApp()
        openBottomNavigation("Bookshelf")
        assertText("Automation Shelf")
    }

    @Test
    fun sortAndReorderControlsOpen() {
        openBookshelf()
        clickDescription("Sort")
        assertText("Sort Type")
        assertText("Default")
        assertText("Recently Updated")
        assertText("Name")
        assertText("Word Count")
        assertText("Reverse")

        pressBack()
        openBookshelfMenu()
        assertText("Adjust Order")
        assertText("Bookshelf Settings")
        assertText("Share Bookshelf")
    }

    @Test
    fun bookshelfRenameAndSettingsPersistAfterRestart() {
        openBookshelf()
        openBookshelfMenu()
        clickText("Bookshelf Settings")

        assertText("Edit Bookshelf")
        setFirstTextField("Renamed Benchmark Shelf")
        clickText("Auto Cache")
        clickText("Update Notification")
        clickDescription("Save")
        assertText("Renamed Benchmark Shelf")

        restartApp()
        openBottomNavigation("Bookshelf")
        assertText("Renamed Benchmark Shelf")
        openBookshelfMenu()
        clickText("Bookshelf Settings")
        assertText("Renamed Benchmark Shelf")
    }

    @Test
    fun emptyBookshelfNameShowsValidationAndDoesNotSave() {
        openBookshelf()
        openBookshelfMenu()
        clickText("Bookshelf Settings")
        clickDescription("Clear text")
        clickDescription("Save")

        assertText("Enter the bookshelf name.")
        assertText("Edit Bookshelf")
    }

    @Test
    fun selectionModeSupportsPinMoveAndCancel() {
        openBookshelf()
        longClickText("Benchmark Sample Novel")
        assertDescription("Select All")
        assertDescription("Settings")
        clickDescription("Selected book actions")
        assertText("Pin / unpin selected books")
        assertText("Remove from this bookshelf")
        assertText("Add to bookshelves")
        clickText("Switch to grid")
        clickDescription("Selected book actions")
        assertText("Switch to list")
        pressBack()

        clickDescription("Cancel")
        assertText("Bookshelf")

        longClickText("Benchmark Sample Novel")
        clickDescription("Selected book actions")
        clickText("Add to bookshelves")
        assertText("Add this book to the following bookshelves")
        assertText("Benchmark Shelf")
        clickText("Cancel")
        assertText("Bookshelf")
    }

    @Test
    fun selectedBookCanBeRemovedFromShelf() {
        openBookshelf()
        longClickText("Benchmark Sample Novel")
        clickDescription("Selected book actions")
        clickText("Remove from this bookshelf")

        assertText("Nothing Here")
        assertTextNotVisible("Benchmark Sample Novel")
    }

    @Test
    fun deleteBookshelfSupportsCancelAndConfirmation() {
        openBookshelf()
        openBookshelfMenu()
        clickText("Bookshelf Settings")
        clickText("Delete Bookshelf")
        assertTextContains("lost forever")
        clickText("Cancel")
        assertText("Edit Bookshelf")

        clickText("Delete Bookshelf")
        clickText("OK")
        assertText("Bookshelf")
        assertTextNotVisible("Benchmark Shelf")
    }
}
