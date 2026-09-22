package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@OptIn(ExperimentalMaterial3Api::class)
class ChapterSelectionBottomSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var volumes by mutableStateOf(BookVolumes("book", listOf(volume("a", 100))))
    private var current by mutableStateOf("a-80")
    private var expanded by mutableStateOf("")
    private var selected = ""
    private var fontScale by mutableStateOf(1f)

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun teardown() { activity.pause().stop().destroy() }

    @Test fun openingRevealsTheCurrentChapterInsideALongVolume() {
        show()
        compose.onNodeWithText("Chapter a-80").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Chapter a-1").assertDoesNotExist()
        compose.onNodeWithText("Chapter a-80").performClick()
        assertEquals("a-80", selected)
    }

    @Test fun firstAndLastChaptersAndLaterVolumesAreVisible() {
        volumes = BookVolumes("book", listOf(volume("a", 10), volume("b", 100), volume("c", 10)))
        current = "b-1"
        show()
        compose.onNodeWithText("Chapter b-1").assertIsDisplayed()
        compose.runOnIdle { current = "b-100" }
        compose.onNodeWithText("Chapter b-100").assertIsDisplayed()
        compose.runOnIdle { current = "c-10" }
        compose.onNodeWithText("Chapter c-10").assertIsDisplayed()
        assertEquals("c", expanded)
    }

    @Test fun partiallyExpandedSheetAlsoRevealsTheCurrentChapter() {
        show(skipPartiallyExpanded = false)
        compose.onNodeWithText("Chapter a-80").assertIsDisplayed().assertIsSelected()
    }

    @Test fun refreshKeepsTheManuallyBrowsedChapterAnchoredById() {
        show()
        list().performScrollToIndex(30)
        compose.onNodeWithText("Chapter a-30").assertIsDisplayed()
        compose.runOnIdle {
            val old = volumes.volumes.single()
            volumes = volumes.copy(volumes = listOf(old.copy(chapters = listOf(ChapterInformation("new", "New prologue")) + old.chapters)))
        }
        compose.onNodeWithText("Chapter a-30").assertIsDisplayed()
        compose.onNodeWithText("Chapter a-80").assertDoesNotExist()
        compose.runOnIdle { fontScale = 1.3f }
        compose.onNodeWithText("Chapter a-30").assertIsDisplayed()
    }

    @Test fun userCanCollapseAndBrowseAnotherVolumeWithoutBeingPulledBack() {
        volumes = BookVolumes("book", listOf(volume("a", 100), volume("b", 10)))
        show()
        list().performScrollToIndex(0)
        compose.onNodeWithText("Volume a").performClick()
        compose.onNodeWithText("Chapter a-80").assertDoesNotExist()
        compose.onNodeWithText("Volume b").performClick()
        compose.onNodeWithText("Chapter b-1").assertIsDisplayed().performClick()
        assertEquals("b-1", selected)
        assertEquals("b", expanded)
    }

    @Test fun unknownAndBlankIdsLeaveTheDirectoryUsableAndCanResolveAfterAnUpdate() {
        current = ""
        show()
        compose.onNodeWithText("Volume a").performClick()
        compose.onNodeWithText("Chapter a-1").assertIsDisplayed()
        compose.runOnIdle { current = "missing" }
        compose.onNodeWithText("Chapter a-1").assertIsDisplayed()
        compose.runOnIdle {
            val old = volumes.volumes.single()
            volumes = volumes.copy(volumes = listOf(old.copy(chapters = old.chapters + ChapterInformation("missing", "New chapter"))))
        }
        compose.onNodeWithText("New chapter").assertIsDisplayed().assertIsSelected()
    }

    @Test fun initiallyEmptyDirectoryLocatesTheCurrentChapterOnceLoaded() {
        volumes = BookVolumes("book", emptyList())
        show()
        compose.waitForIdle()
        compose.runOnIdle { volumes = volumes.copy(volumes = listOf(volume("a", 100))) }
        compose.onNodeWithText("Chapter a-80").assertIsDisplayed()
    }

    @Test fun tenThousandChaptersComposeOnlyTheViewportAndReachTheLastChapter() {
        volumes = BookVolumes("book", listOf(volume("a", 10_000)))
        current = "a-8000"
        show()
        compose.onNodeWithText("Chapter a-8000").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Chapter a-", substring = true).fetchSemanticsNodes().size < 30)
        list().performScrollToIndex(10_000)
        compose.onNodeWithText("Chapter a-10000").assertIsDisplayed().performClick()
        assertEquals("a-10000", selected)
    }

    @Test fun largeTypeKeepsALongSelectedTitleVisibleAndClickable() {
        fontScale = 1.8f
        val chapter = ChapterInformation("long", "A long chapter title with several words that wraps across lines")
        volumes = BookVolumes("book", listOf(Volume("a", "A very long volume title with several words", listOf(chapter))))
        current = chapter.id
        show()
        compose.onNode(hasText(chapter.title)).assertIsDisplayed().assertIsSelected().performClick()
        assertEquals("long", selected)
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun show(skipPartiallyExpanded: Boolean = true) {
        compose.runOnUiThread {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme {
                        ChapterSelectionBottomSheet(rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
                            expanded, volumes, current, {}, { selected = it }, { expanded = it })
                    }
                }
            }
        }
    }

    private fun volume(id: String, count: Int) = Volume(id, "Volume $id", (1..count).map {
        ChapterInformation("$id-$it", "Chapter $id-$it")
    })
}
