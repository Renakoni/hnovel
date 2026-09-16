package indi.renakoni.nextvol.ui.home.categories

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageContent
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageState
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryButton
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
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w320dp-h800dp")
// Wrapping assertions need real text measurement; legacy graphics uses approximate widths.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoryTagLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val source = Identifier("fixture", "category-tags")
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun category(id: String, title: String, target: String = "/tags/$id") =
        SourceDiscoveryCategory(id, title, SourceDiscoveryTarget(source, target))

    private fun show(categories: List<SourceDiscoveryCategory>, scale: Float = 1f,
        onCategory: (SourceDiscoveryCategory) -> Unit = {}, onAction: (String, Boolean) -> Unit = { _, _ -> }) {
        val listing = SourceListing(SourceMetadata(WebDataSourceItem(source, "Fixture", "Fixture"),
            setOf(SourceCapability.Categories)), SourceStatus.Ready)
        val state = DiscoveryPageState(sources = listOf(listing), selected = source, content = mapOf(source to
            DiscoveryPageContent(loaded = true, categories = categories, buttons = listOf(DiscoveryButton("next", "Next tags")))))
        activity.get().setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme { CategoriesScreen(state, {}, onCategory, { _, _ -> }, {}, {}, {}, {}, onAction = onAction) }
            }
        }
    }

    @Test fun tagsShareRowsWrapAndKeepHeadingsAndNavigation() {
        val items = listOf(category("heading", "Themes", ""), category("a", "Art"), category("b", "Life"),
            category("c", "History"), category("d", "Science fiction"))
        var clicked: SourceDiscoveryCategory? = null
        show(items, onCategory = { clicked = it })
        val heading = compose.onNodeWithText("Themes")
        heading.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertHasNoClickAction()
        val a = compose.onNodeWithText("Art").assertTouchHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("Life").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText("Science fiction").fetchSemanticsNode().boundsInRoot
        assertEquals(a.top, b.top, 1f)
        assertTrue(b.left >= a.right)
        assertTrue(last.top > a.top)
        compose.onNodeWithText("Science fiction").performClick()
        assertEquals(items.last(), clicked)
    }

    @Test fun largeTextWrapsInsideTheViewportAndPaginationRemainsReachable() {
        val title = "A long category label that must remain readable without clipping"
        val items = listOf(category("long", title)) + (1..60).map { category("$it", "Tag $it") }
        var next = false
        show(items, scale = 1.5f, onAction = { id, _ -> next = id == "next" })
        val long = compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(long.left >= screen.left && long.right <= screen.right)
        assertTrue(long.height > compose.onNodeWithText("Tag 1").fetchSemanticsNode().boundsInRoot.height)
        compose.onNodeWithText("Tag 60").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Next tags").assertIsDisplayed().performClick()
        assertTrue(next)
    }
}
