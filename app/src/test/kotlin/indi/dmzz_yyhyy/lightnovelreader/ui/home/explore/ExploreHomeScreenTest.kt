package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.home.ExploreHomeScreen
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ExploreHomeScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun listing(id: Identifier, capabilities: Set<SourceCapability> = setOf(SourceCapability.Explore, SourceCapability.Search, SourceCapability.Categories)) =
        SourceListing(SourceMetadata(WebDataSourceItem(id, id.id, "fixture"), capabilities), SourceStatus.Ready)
    private fun content(id: Identifier) = DiscoveryPageContent(loaded = true, sections = listOf(
        SourceDiscoverySection("list", "Recommended", listOf(SourceDiscoveryBook(SourceBookId(id, "same"), "Same book", "", "")),
            SourceDiscoveryTarget(id, "all"))))

    @Test fun sourceTabsAreTheOnlyTabsAndBooksAndMoreKeepTheirOwningSource() {
        val a = Identifier("fixture", "Source A")
        val b = Identifier("fixture", "Source B")
        var state by mutableStateOf(DiscoveryPageState(listOf(listing(a), listing(b)), a,
            mapOf(a to content(a), b to content(b))))
        val books = mutableListOf<SourceBookId>()
        val more = mutableListOf<SourceDiscoverySection>()
        var search: Identifier? = null
        var categories: Identifier? = null
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(state, { state = state.copy(selected = it) }, { _, _ -> }, {}, { more += it }, { books += it },
                { search = state.selected }, { categories = state.selected }, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(2)
        compose.onNodeWithText("Source A").assertIsSelected()
        compose.onNode(hasClickAction() and hasText("Same book")).performClick()
        compose.onNodeWithContentDescription("Show more").performClick()
        compose.onNodeWithText("Source B").performClick().assertIsSelected()
        compose.onNode(hasClickAction() and hasText("Same book")).performClick()
        compose.onNodeWithContentDescription("Show more").performClick()
        compose.onNodeWithContentDescription("Search this source").performClick()
        compose.onNodeWithText("Categories").performClick()
        assertEquals(listOf(a, b), books.map { it.sourceId })
        assertEquals(listOf(a, b), more.map { it.more!!.sourceId })
        assertEquals(b, search)
        assertEquals(b, categories)
    }

    @Test fun oneRealSourceAndItsUnsupportedSearchDoNotCreatePlaceholders() {
        val id = Identifier("fixture", "Only source")
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id, setOf(SourceCapability.Explore))), id, mapOf(id to content(id))),
                {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(1)
        compose.onNodeWithText("Only source").assertIsSelected()
        compose.onNodeWithContentDescription("Search this source").assertIsNotEnabled()
        compose.onNodeWithText("Categories").assertDoesNotExist()
    }

    @Test fun largeFeedCanOpenTheLastSectionWithoutLosingItsCategoryOrSource() {
        val id = Identifier("fixture", "Large source")
        val sections = List(326) { index -> SourceDiscoverySection("$index", "Category $index", emptyList(),
            SourceDiscoveryTarget(id, "/category/$index"), "category-$index") }
        val opened = mutableListOf<SourceDiscoverySection>()
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id,
                mapOf(id to DiscoveryPageContent(loaded = true, sections = sections))),
                {}, { _, _ -> }, {}, { opened += it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(sections.lastIndex)
        compose.onNodeWithText("Category 325").assertExists()
        compose.onAllNodesWithContentDescription("Show more").onLast().performClick()
        assertEquals(listOf(sections.last()), opened)
    }

    @Test fun emptySourceStateOffersManagementAndNoFakeTabs() {
        var opened = 0
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(), {}, { _, _ -> }, {}, {}, {}, {}, {}, { opened++ }, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(0)
        compose.onNodeWithText("Book sources").performClick()
        assertEquals(1, opened)
    }

    @Test fun ruleInputsAndActionsShareTheFeedAndFailuresKeepVisibleContent() {
        val id = Identifier("fixture", "Rule source")
        val input = mutableListOf<Pair<String, String>>()
        val actions = mutableListOf<Pair<String, Boolean>>()
        val page = content(id).copy(error = DiscoveryError.Network,
            filters = listOf(DiscoveryFilter.Choice("sort", "Sort", linkedMapOf("new" to "New", "popular" to "Popular"), "new")),
            values = mapOf("sort" to "new"), buttons = listOf(DiscoveryButton("login", "Sign in")))
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, { key, value -> input += key to value },
                { key, long -> actions += key to long }, {})
        } }
        compose.onNodeWithText("Sort: New").performClick()
        compose.onNodeWithText("Popular").performClick()
        compose.onNodeWithText("Sign in").performClick()
        compose.onNodeWithText("Sign in").performTouchInput { longClick() }
        compose.onNode(hasClickAction() and hasText("Same book")).performScrollTo().assertExists()
        assertEquals(listOf("sort" to "popular"), input)
        assertEquals(listOf("login" to false, "login" to true), actions)
    }
}
