package indi.renakoni.nextvol.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.categories.CategoriesScreen
import indi.renakoni.nextvol.ui.home.discovery.*
import indi.renakoni.nextvol.ui.home.explore.home.ExploreHomeScreen
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
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
                {}, { _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
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
}
