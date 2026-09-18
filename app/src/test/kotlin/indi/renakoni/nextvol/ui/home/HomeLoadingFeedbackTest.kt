package indi.renakoni.nextvol.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.explore.SourceSearchFailure
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.categories.CategoriesScreen
import indi.renakoni.nextvol.ui.home.discovery.*
import indi.renakoni.nextvol.ui.home.explore.home.ExploreHomeScreen
import indi.renakoni.nextvol.ui.home.explore.search.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLoadingFeedbackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val id = Identifier("fixture", "one")
    private val source = SourceListing(SourceMetadata(WebDataSourceItem(id, "Wenku8", "fixture"),
        setOf(SourceCapability.Categories, SourceCapability.Explore)), SourceStatus.Ready)
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    private fun advance() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun singleCircle(): androidx.compose.ui.geometry.Rect {
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(1)
        val bounds = compose.onNode(progress, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("progress must be circular, not a full-width line", bounds.width, bounds.height, 1f)
        assertTrue("progress must remain compact", bounds.width <= 48)
        return bounds
    }

    @Test fun categoryLoadingUsesRefreshActionWithoutMovingExistingContent() {
        var refreshed = 0
        var page by mutableStateOf(DiscoveryPageContent(loaded = true, categories = listOf(
            SourceDiscoveryCategory("one", "Category one", SourceDiscoveryTarget(id, "/one")))))
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(listOf(source), id, mapOf(id to page)),
                {}, {}, { _, _ -> }, { refreshed++ }, {}, {}, {})
        } }
        val before = compose.onNodeWithText("Category one").fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithText("Wenku8").fetchSemanticsNode().boundsInRoot
        val refresh = compose.onNodeWithContentDescription("Refresh").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { page = page.copy(loading = true, acting = true) }
        advance()
        val circle = singleCircle()
        assertTrue("progress belongs in the refresh action", refresh.contains(circle.center))
        assertTrue("progress must not overlap the tabs or category buttons", circle.bottom <= tab.top)
        compose.onNodeWithContentDescription("Refresh").assertIsEnabled().performClick()
        assertEquals(1, refreshed)
        assertEquals(before, compose.onNodeWithText("Category one").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { page = page.copy(loading = false, acting = false) }
        advance()
        compose.onAllNodes(progress).assertCountEquals(0)
        assertEquals(before, compose.onNodeWithText("Category one").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun discoveryActionSharesRefreshFeedbackWithoutMovingTheFeed() {
        var page by mutableStateOf(DiscoveryPageContent(loaded = true, sections = listOf(
            SourceDiscoverySection("one", "Recommended", emptyList(), SourceDiscoveryTarget(id, "/one")))))
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(source), id, mapOf(id to page)),
                {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        val before = compose.onNodeWithText("Recommended").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { page = page.copy(loading = true, acting = true) }
        advance()
        singleCircle()
        assertEquals(before, compose.onNodeWithText("Recommended").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { page = page.copy(loading = false, acting = false) }
        advance()
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun sourceSearchHasOneIndicatorAcrossInitialAndStreamingResults() {
        val state = MutableExploreSearchUiState().apply {
            sourceName = "Wenku8"
            query = "Novel"
            submittedKeyword = query
            searchBarExpanded = false
        }
        val opened = mutableListOf<String>()
        activity.get().setContent { MaterialTheme {
            ExploreSearchScreen(state, {}, {}, {}, {}, {}, {}, {}, { opened += it }, {}, {})
        } }
        advance()
        singleCircle()
        compose.runOnIdle {
            state.isLoading = false
            state.searchResult += "first" to flowOf(Ok(book()))
        }
        advance()
        val first = compose.onNode(hasClickAction() and hasText("First result"))
        val before = first.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("stream progress belongs after the results", singleCircle().top >= before.bottom)
        first.performClick()
        assertEquals(listOf("first"), opened)
        compose.runOnIdle { state.isLoadingComplete = true }
        advance()
        compose.onAllNodes(progress).assertCountEquals(0)
        assertEquals(before, first.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state.searchResult.clear() }
        advance()
        compose.onNodeWithText(activity.get().getString(R.string.search_no_results)).assertIsDisplayed()
        compose.runOnIdle {
            state.isLoadingComplete = false
            state.isLoading = true
            state.failure = SourceSearchFailure(DiscoveryError.Unavailable)
        }
        advance()
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun aggregateSearchProgressStaysWithItsSourceAndDoesNotMoveTheTitle() {
        var state by mutableStateOf(SearchHubState(query = "Novel", submittedKeyword = "Novel", sources = listOf(
            SearchHubSource(id, "Source one", loading = true),
            SearchHubSource(Identifier("fixture", "two"), "Source two", searched = true))))
        activity.get().setContent { MaterialTheme {
            SearchHubScreen(state, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {})
        } }
        advance()
        val title = compose.onAllNodesWithText("Source one", useUnmergedTree = true).onLast()
        val before = title.fetchSemanticsNode().boundsInRoot
        assertEquals(before.center.y, singleCircle().center.y, 1f)
        compose.runOnIdle { state = state.copy(sources = state.sources.map { it.copy(loading = false, searched = true) }) }
        advance()
        compose.onAllNodes(progress).assertCountEquals(0)
        assertEquals(before, title.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun largeFontSearchStatusWrapsAndFailureActionsKeepWholeLabels() {
        val state = MutableExploreSearchUiState().apply {
            sourceName = "Wenku8"
            submittedKeyword = "A long novel title with mixed 中文 characters ".repeat(4)
            query = submittedKeyword
            searchBarExpanded = false
            isLoading = false
            isLoadingComplete = true
            failure = SourceSearchFailure(DiscoveryError.Unavailable)
        }
        var returned = 0
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme { ExploreSearchScreen(state, {}, {}, { returned++ }, {}, {}, {}, {}, {}, {}, {}) }
            }
        }
        val heading = activity.get().getString(R.string.search_results_title, state.submittedKeyword, 0, "")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(heading, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertTrue("the status must ellipsize within its available width", layouts.single().isLineEllipsized(1))
        layouts.clear()
        compose.onNodeWithText("Back", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("wrap whole actions instead of breaking their labels", 1, layouts.single().lineCount)
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        assertEquals(1, returned)
    }

    private fun book() = BookInformation(id = "first", title = "First result", author = "Author", description = "",
        publishingHouse = "", wordCount = WordCount(100), lastUpdated = LocalDateTime.of(2026, 9, 17, 0, 0), isComplete = false)
}
