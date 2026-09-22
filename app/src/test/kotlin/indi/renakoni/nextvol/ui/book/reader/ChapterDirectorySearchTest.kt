package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@OptIn(ExperimentalMaterial3Api::class)
class ChapterDirectorySearchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var volumes by mutableStateOf(BookVolumes("book", listOf(volume("a", 100))))
    private var current by mutableStateOf("a-80")
    private var expanded by mutableStateOf("")
    private var selected = ""
    private var dismissed = false
    private var fontScale by mutableStateOf(1f)

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun teardown() { activity.pause().stop().destroy() }

    @Test fun searchingByTitleOrNumberSelectsTheOriginalIdAndKeepsTheCurrentMark() {
        show()
        query("a-80", 1)
        compose.onNodeWithText("Chapter a-80").assertIsDisplayed().assertIsSelected()
        query("a-99", 1)
        compose.onNodeWithText("Chapter a-99").performClick()
        assertEquals("a-99", selected)
    }

    @Test fun duplicateTitlesAreDistinguishedByVolumeAndJumpToTheCorrectChapter() {
        volumes = BookVolumes("book", listOf(
            Volume("a", "First volume", listOf(ChapterInformation("a-interlude", "Interlude"))),
            Volume("b", "Second volume", listOf(ChapterInformation("b-interlude", "Interlude"))),
        ))
        current = "a-interlude"
        show()
        query("INTERLUDE", 2)
        compose.onNode(hasText("Interlude") and hasText("Second volume")).assertIsDisplayed().performClick()
        assertEquals("b-interlude", selected)
    }

    @Test fun clearingSearchRestoresTheManuallyBrowsedDirectoryPosition() {
        show()
        list().performScrollToIndex(30)
        compose.onNodeWithText("Chapter a-30").assertIsDisplayed()
        query("Chapter", 100)
        list().performScrollToIndex(90)
        compose.onNodeWithContentDescription("Clear chapter search").performClick()
        compose.onNodeWithText("Chapter a-30").assertIsDisplayed()
        compose.onNodeWithText("Chapter a-80").assertDoesNotExist()
        assertEquals("", selected)
        assertEquals("a", expanded)
    }

    @Test fun whitespaceAndNoMatchDoNotChangeReadingOrVolumeSelection() {
        show()
        input().performTextReplacement("   ")
        compose.onNodeWithText("Chapter a-80").assertIsDisplayed()
        query("Not in this directory", 0)
        compose.onNodeWithText("No matching chapters").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear chapter search").performClick()
        compose.onNodeWithText("Chapter a-80").assertIsDisplayed().assertIsSelected()
        assertEquals("", selected)
        assertEquals("a", expanded)
    }

    @Test fun updatedDirectoryReplacesResultsWithoutChangingTheQuery() {
        show()
        query("new", 0)
        compose.runOnIdle {
            val old = volumes.volumes.single()
            volumes = volumes.copy(volumes = listOf(old.copy(chapters = old.chapters + ChapterInformation("opaque:next", "New chapter"))))
        }
        awaitCount(1)
        compose.onNodeWithText("New chapter").assertIsDisplayed().performClick()
        assertEquals("opaque:next", selected)
    }

    @Test fun newQueriesReplaceOldResultsAndTenThousandMatchesRemainLazy() {
        volumes = BookVolumes("book", listOf(volume("a", 10_000)))
        show()
        query("Chapter", 10_000)
        assertTrue(compose.onAllNodesWithText("Chapter a-", substring = true).fetchSemanticsNodes().size < 30)
        list().performScrollToIndex(9_999)
        compose.onNodeWithText("Chapter a-10000").assertIsDisplayed()
        input().performTextReplacement("a-7777")
        input().performTextReplacement("a-8888")
        awaitCount(1)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Chapter a-8888").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Chapter a-8888").assertIsDisplayed().performClick()
        assertEquals("a-8888", selected)
        compose.onNodeWithText("Chapter a-7777").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [27, 35])
    fun keyboardSearchActionKeepsResultsUsableAtLargeType() {
        fontScale = 1.8f
        show()
        query("a-90", 1)
        input().performImeAction()
        input().assertIsNotFocused()
        compose.onNodeWithText("Chapter a-90").assertIsDisplayed().performClick()
        assertEquals("a-90", selected)
    }

    @Test
    @Config(sdk = [27, 35])
    fun backClosesSearchBeforeDismissingTheDirectory() {
        show()
        query("a-90", 1)
        input().performImeAction()
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Chapter a-80").assertIsDisplayed()
        compose.onNodeWithText("1 chapter found").assertDoesNotExist()
        assertFalse(dismissed)
    }

    @Test fun selectingAResultThenClearingShowsTheNewCurrentChapter() {
        volumes = BookVolumes("book", listOf(volume("a", 100), volume("b", 100)))
        show()
        query("b-95", 1)
        compose.onNodeWithText("Chapter b-95").performClick()
        compose.runOnIdle { current = selected }
        compose.onNodeWithText("Chapter b-95").assertIsSelected()
        compose.onNodeWithContentDescription("Clear chapter search").performClick()
        compose.onNodeWithText("Chapter b-95").assertIsDisplayed().assertIsSelected()
        assertEquals("b", expanded)
    }

    @Test fun searchingAnEmptyDirectoryShowsAnEmptyResultInsteadOfAnEndlessSpinner() {
        volumes = BookVolumes("book", emptyList())
        show()
        query("Chapter", 0)
        compose.onNodeWithText("No matching chapters").assertIsDisplayed()
    }

    private fun input() = compose.onNode(hasSetTextAction())
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun query(text: String, count: Int) {
        input().performTextReplacement(text)
        awaitCount(count)
    }
    private fun awaitCount(count: Int) {
        val text = if (count == 1) "1 chapter found" else "$count chapters found"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun show() {
        compose.runOnUiThread {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme {
                        ChapterSelectionBottomSheet(rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            expanded, volumes, current, { dismissed = true }, { selected = it }, { expanded = it })
                    }
                }
            }
        }
    }
    private fun volume(id: String, count: Int) = Volume(id, "Volume $id", (1..count).map {
        ChapterInformation("$id-$it", "Chapter $id-$it")
    })
}
