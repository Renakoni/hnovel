package indi.dmzz_yyhyy.lightnovelreader.ui.home

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.categories.CategoriesScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryPageState
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.home.ExploreHomeScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.home.reading.home.ReadingTopBar
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
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalMaterial3Api::class)
class HomeSettingsActionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var view: View
    private lateinit var insets: WindowInsets
    private lateinit var density: Density
    private var root by mutableStateOf("Reading")
    private var opened = 0
    private var pinned = 0
    private var removed = 0
    private var collected = 0
    private val shelf = MutableBookshelfHomeUiState(onPin = { pinned++ }, onRemove = { removed++ }, onMarkSelectedBooks = { collected++ })
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        WindowCompat.setDecorFitsSystemWindows(activity.get().window, false)
        activity.get().setContent { MaterialTheme {
            view = LocalView.current
            insets = WindowInsets.safeDrawing
            density = LocalDensity.current
            val settings = { opened++; Unit }
            val id = Identifier("fixture", "source")
            val state = DiscoveryPageState(listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Source", "fixture"),
                setOf(SourceCapability.Explore, SourceCapability.Categories, SourceCapability.Search)), SourceStatus.Ready)), id)
            Box(Modifier.fillMaxSize().testTag("screen")) {
                when (root) {
                    "Reading" -> ReadingTopBar({}, {}, settings)
                    "Bookshelf" -> BookshelfHomeTopBar(TopAppBarDefaults.pinnedScrollBehavior(), MaterialTheme.colorScheme.surface,
                        shelf, {}, {}, {}, {}, settings)
                    "Explore" -> ExploreHomeScreen(state, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, settings)
                    "Categories" -> CategoriesScreen(state, {}, {}, { _, _ -> }, {}, {}, settings, {})
                    "Empty" -> CategoriesScreen(DiscoveryPageState(), {}, {}, { _, _ -> }, {}, {}, settings, {})
                }
            }
        } }
        compose.waitForIdle()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    private fun checkAction() {
        compose.runOnIdle {
            val injected = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(20, 24, 28, 32))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), Insets.of(20, 24, 28, 32))
                .setVisible(WindowInsetsCompat.Type.systemBars(), true).build()
            ViewCompat.dispatchApplyWindowInsets(view, injected)
        }
        val action = compose.onNodeWithContentDescription("Settings").assertIsDisplayed().assertHasClickAction()
        val bounds = action.fetchSemanticsNode().touchBoundsInRoot
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        assertEquals("test system bars reached Compose", 28, insets.getRight(density, LayoutDirection.Ltr))
        assertTrue("settings remains within the safe right inset: $root $bounds in $screen", bounds.right <= screen.right - 28 + 1)
        assertTrue("settings remains below the safe top inset", bounds.top >= 24)
        assertTrue("minimum touch target", bounds.width >= 48 && bounds.height >= 48)
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val other = node.touchBoundsInRoot
            if (other != bounds && other.top <= bounds.center.y && other.bottom >= bounds.center.y) {
                assertTrue("actions must not overlap settings", other.right <= bounds.left + 1)
                assertTrue("actions stay within the left inset", other.left >= 20)
            }
        }
        action.performClick()
    }

    @Test fun fourExistingTopBarsKeepSettingsLastAndReachableOnNarrowScreenWithInsets() {
        for (name in listOf("Reading", "Bookshelf", "Explore", "Categories", "Empty")) {
            compose.runOnIdle { root = name }
            checkAction()
        }
        assertEquals(5, opened)
    }

    @Test
    @Config(qualifiers = "en-rUS-w720dp-h360dp-land-mdpi")
    fun wideLandscapeTopBarsStillPlaceSettingsAtTheRightEdge() {
        for (name in listOf("Reading", "Bookshelf", "Explore", "Categories")) {
            compose.runOnIdle { root = name }
            checkAction()
        }
        assertEquals(4, opened)
    }

    @Test fun selectedBooksKeepLayoutAndAllActionsReachableBesideSettings() {
        compose.runOnIdle { root = "Bookshelf"; shelf.selectMode = true }
        checkAction()
        compose.onNodeWithContentDescription("Selected book actions").performClick()
        compose.onNodeWithText("Pin / unpin selected books").performClick()
        compose.onNodeWithContentDescription("Selected book actions").performClick()
        compose.onNodeWithText("Remove from this bookshelf").performClick()
        compose.onNodeWithContentDescription("Selected book actions").performClick()
        compose.onNodeWithText("Add to bookshelves").performClick()
        assertEquals(1, pinned)
        assertEquals(1, removed)
        assertEquals(1, collected)
        assertEquals(1, opened)
    }
}
