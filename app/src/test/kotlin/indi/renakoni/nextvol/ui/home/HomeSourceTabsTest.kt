package indi.renakoni.nextvol.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.categories.CategoriesScreen
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageState
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
class HomeSourceTabsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun categoriesKeepLongMixedCharacterTabsBoundedAndSelectable() = checkTabs(explore = false)
    @Test fun exploreKeepsLongMixedCharacterTabsBoundedAndSelectable() = checkTabs(explore = true)
    @Test fun categoriesKeepShortTabsCompactAndNearTheStart() = checkTabs(explore = false, short = true)
    @Test fun exploreKeepsShortTabsCompactAndNearTheStart() = checkTabs(explore = true, short = true)

    private fun checkTabs(explore: Boolean, short: Boolean = false) {
        val names = if (short) listOf("Wenku8", "晋江", "Books")
            else listOf("🏷晋江文学【阅读版本请用3.6版本及以上】", "追书神器~m.zhuishushenqi.com", "🔤 BestLightNovel")
        val sources = names.mapIndexed { index, name ->
            SourceListing(SourceMetadata(WebDataSourceItem(Identifier("fixture", "$index"), name, "fixture"),
                setOf(SourceCapability.Explore, SourceCapability.Categories)), SourceStatus.Ready)
        }
        var state by mutableStateOf(DiscoveryPageState(sources, sources.first().metadata.id))
        val selected = mutableListOf<Identifier>()
        val select: (Identifier) -> Unit = { selected += it; state = state.copy(selected = it) }
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme {
                    if (explore) ExploreHomeScreen(state, select, { _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
                    else CategoriesScreen(state, select, {}, { _, _ -> }, {}, {}, {}, {})
                }
            }
        }
        val first = compose.onNodeWithText(names.first()).assertIsSelected().assertIsDisplayed()
        assertTrue("leave room for neighbouring tabs", first.fetchSemanticsNode().size.width <= 272)
        first.assertHeightIsAtLeast(48.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(names.first(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        if (short) {
            assertTrue("short tabs must size to their content", first.fetchSemanticsNode().size.width <= 160)
            val label = compose.onNodeWithText(names.first(), useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("the first label must stay near the start edge", label.positionInRoot.x <= 32)
            assertFalse("short names must remain complete", layouts.single().isLineEllipsized(0))
        } else {
            assertTrue("long names must ellipsize instead of clipping both ends", layouts.single().isLineEllipsized(0))
        }
        for (name in names.drop(1) + names.first()) {
            val tab = compose.onNodeWithText(name).performScrollTo().performClick().assertIsSelected()
            assertTrue("selected tab stays bounded", tab.fetchSemanticsNode().size.width <= 272)
        }
        assertEquals(listOf(sources[1], sources[2], sources[0]).map { it.metadata.id }, selected)
    }
}
