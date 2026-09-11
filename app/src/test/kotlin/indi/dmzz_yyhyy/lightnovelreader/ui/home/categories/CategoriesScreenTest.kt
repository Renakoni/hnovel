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

    @Test fun sourceTabsAndCategoryClicksKeepOwningIdentityAndBackCallback() {
        val a = Identifier("fixture", "a")
        val b = Identifier("fixture", "b")
        var state by mutableStateOf(CategoriesState(listOf(listing(a, "Source A"), listing(b, "Source B")), a,
            mapOf(a to CategoryContent(listOf(category(a)), loaded = true), b to CategoryContent(listOf(category(b)), loaded = true))))
        val clicked = mutableListOf<SourceDiscoveryCategory>()
        var backs = 0
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(state, { state = state.copy(selected = it) }, { clicked += it }, { _, _ -> }, {}, {}, {}, { backs++ })
        } }
        compose.onNodeWithText("Source A").assertIsSelected()
        compose.onNodeWithText("Same category").performClick()
        compose.onNodeWithText("Source B").performClick().assertIsSelected()
        compose.onNodeWithText("Same category").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf(a, b), clicked.map { it.target.sourceId })
        assertEquals(1, backs)
    }

    @Test fun oneSourceStillDisplaysItsTab() {
        val id = Identifier("fixture", "one")
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(CategoriesState(listOf(listing(id, "Only source")), id,
                mapOf(id to CategoryContent(listOf(category(id)), loaded = true))), {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Only source").assertIsSelected()
        compose.onNodeWithText("Same category").assertExists()
    }

    @Test fun emptyStateOpensSourceManagement() {
        var opened = 0
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(CategoriesState(), {}, {}, { _, _ -> }, {}, { opened++ }, {}, {})
        } }
        compose.onNodeWithText("No enabled book source provides categories. Add or enable a source in source management.").assertExists()
        compose.onNodeWithText("Book sources").performClick()
        assertEquals(1, opened)
    }
}
