package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.*
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
class CategoriesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun listing(id: Identifier, name: String) = SourceListing(
        SourceMetadata(WebDataSourceItem(id, name, "fixture"), setOf(SourceCapability.Categories)), SourceStatus.Ready)
    private fun category(id: Identifier) = SourceDiscoveryCategory("category", "Same category", SourceDiscoveryTarget(id, "tag"))

    @Test fun sourceTabsAndCategoryClicksKeepOwningIdentityAndSettingsCallback() {
        val a = Identifier("fixture", "a")
        val b = Identifier("fixture", "b")
        var state by mutableStateOf(DiscoveryPageState(listOf(listing(a, "Source A"), listing(b, "Source B")), a,
            mapOf(a to DiscoveryPageContent(listOf(category(a)), loaded = true), b to DiscoveryPageContent(listOf(category(b)), loaded = true))))
        val clicked = mutableListOf<SourceDiscoveryCategory>()
        var settings = 0
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(state, { state = state.copy(selected = it) }, { clicked += it }, { _, _ -> }, {}, {}, { settings++ }, {})
        } }
        compose.onNodeWithText("Source A").assertIsSelected()
        compose.onNodeWithText("Same category").performClick()
        compose.onNodeWithText("Source B").performClick().assertIsSelected()
        compose.onNodeWithText("Same category").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertEquals(listOf(a, b), clicked.map { it.target.sourceId })
        assertEquals(1, settings)
    }

    @Test fun oneSourceStillDisplaysItsTab() {
        val id = Identifier("fixture", "one")
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(listOf(listing(id, "Only source")), id,
                mapOf(id to DiscoveryPageContent(listOf(category(id)), loaded = true))), {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Only source").assertIsSelected()
        compose.onNodeWithText("Same category").assertExists()
    }

    @Test fun emptyStateOpensSourceManagement() {
        var opened = 0
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(), {}, {}, { _, _ -> }, {}, { opened++ }, {}, {})
        } }
        compose.onNodeWithText("No enabled book source provides categories. Add or enable a source in source management.").assertExists()
        compose.onNodeWithText("Book sources").performClick()
        assertEquals(1, opened)
    }

    @Test fun sourceFormChoicesAndButtonsUseHostCallbacksInsideOneSourceTab() {
        val id = Identifier("fixture", "actions")
        val values = mutableListOf<Pair<String, String>>()
        val actions = mutableListOf<Pair<String, Boolean>>()
        val page = DiscoveryPageContent(listOf(category(id)), loaded = true,
            filters = listOf(DiscoveryFilter.Choice("Sort", "Sort", linkedMapOf("new" to "New", "popular" to "Popular"), "new")),
            values = mapOf("Sort" to "new"), buttons = listOf(DiscoveryButton("login", "Sign in")))
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(listOf(listing(id, "Rule source")), id, mapOf(id to page)),
                {}, {}, { _, _ -> }, {}, {}, {}, {}, onInput = { key, value -> values += key to value },
                onAction = { key, long -> actions += key to long })
        } }
        compose.onNodeWithText("Rule source").assertIsSelected()
        compose.onNodeWithText("Sort: New").performClick()
        compose.onNodeWithText("Popular").performClick()
        compose.onNodeWithText("Sign in").performClick()
        compose.onNodeWithText("Sign in").performTouchInput { longClick() }
        assertEquals(listOf("Sort" to "popular"), values)
        assertEquals(listOf("login" to false, "login" to true), actions)
    }

    @Test fun waitingForTheFirstSnapshotDoesNotDisplayTheEmptySourceMessage() {
        var state by mutableStateOf(DiscoveryPageState(loadingSources = true))
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(state, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        val message = "No enabled book source provides categories. Add or enable a source in source management."
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithText("Book sources").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loadingSources = false) }
        compose.onNodeWithText(message).assertExists()
    }
}
