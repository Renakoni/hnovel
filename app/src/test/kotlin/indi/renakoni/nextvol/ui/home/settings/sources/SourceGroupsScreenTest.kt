package indi.renakoni.nextvol.ui.home.settings.sources

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import hnovel.imports.*
import indi.renakoni.nextvol.data.web.rules.*
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SourceGroupsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val model = mockk<SourcesViewModel>(relaxed = true)
    private val groups = listOf(SourceGroup("work", "Work"), SourceGroup("empty", "Empty"))
    private fun source(id: String, group: String? = null): InstalledRuleSource {
        val definition = SourceDefinition(id, "legado", LEGADO_PROFILE, "https://$id.invalid/", id,
            true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        return InstalledRuleSource(definition, emptyList(), null, preferences = SourcePreferences(true, false, groupId = group))
    }
    private val initial = SourceManagementState(groups = groups, installed = listOf(source("One"), source("Two", "work")))
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun groupManagementIsBesideAddAndCreatesAnEmptyGroupWithoutImporting() {
        activity.get().setContent { MaterialTheme { SourcesScreen(initial, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Add book source").assertIsDisplayed()
        assertEquals(compose.onNodeWithText("Add book source").fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithText("Manage groups").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("Manage groups").assertIsDisplayed().performClick()
        compose.onNodeWithText("New group").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Weekend")
        compose.onNodeWithText("Save").performClick()
        verify(exactly = 1) { model.createGroup("Weekend", emptySet()) }
        verify(exactly = 0) { model.previewCatalog(any()) }
        verify(exactly = 0) { model.previewUrl(any(), any()) }
    }

    @Test fun batchSelectionSurvivesFilteringAndMovesOnlySelectedSources() {
        var state by mutableStateOf(initial.copy(message = indi.renakoni.nextvol.R.string.source_groups_saved, groupRevision = 1))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Select sources").performScrollTo().performClick()
        compose.onNodeWithText("One").performScrollTo().performClick()
        compose.onNodeWithText("Selected: 1").assertIsDisplayed()
        compose.onNodeWithText("Work").performScrollTo().performClick()
        compose.onNodeWithText("One").assertDoesNotExist()
        compose.onNodeWithText("Two").performScrollTo().performClick()
        compose.onNodeWithText("Selected: 2").assertIsDisplayed()
        compose.onNodeWithText("Move to group").performClick()
        compose.onNode(hasText("Empty") and hasAnyAncestor(isDialog())).performClick()
        verify(exactly = 1) { model.moveToGroup(setOf(Identifier("rules", "One"), Identifier("rules", "Two")), "empty") }
        verify(exactly = 0) { model.setEnabled(any(), any()) }
        compose.runOnIdle { state = state.copy(message = indi.renakoni.nextvol.R.string.source_groups_saved,
            groupRevision = 2,
            installed = state.installed.map { it.copy(preferences = it.preferences.copy(groupId = "empty")) }) }
        compose.onNodeWithText("Selected: 2").assertDoesNotExist()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun duplicateNamesAreRejectedAndDeletingAGroupRequiresConfirmation() {
        activity.get().setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                SourcesScreen(initial, model, onDiagnostics = {}) {}
            }
        } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNodeWithText("New group").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(" work ")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Delete group Work").performClick()
        compose.onNodeWithText("Sources in this group will become ungrouped. Your sources and reading data will be kept.").assertIsDisplayed()
        verify(exactly = 0) { model.deleteGroup(any()) }
        compose.onNodeWithText("Cancel").performClick()
        verify(exactly = 0) { model.deleteGroup(any()) }
        compose.onNodeWithContentDescription("Delete group Work").performClick()
        compose.onNodeWithText("Remove source").assertDoesNotExist()
        compose.onNodeWithText("Delete").performClick()
        verify(exactly = 1) { model.deleteGroup("work") }
        verify(exactly = 0) { model.remove(any()) }
    }

    @Test fun singleSourceCanCreateItsGroupWithoutOpeningSourceSettings() {
        activity.get().setContent { MaterialTheme { SourcesScreen(initial, model, onDiagnostics = {}) {} } }
        compose.onNodeWithContentDescription("Change group for One").performScrollTo().performClick()
        compose.onNodeWithText("New group").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Weekend")
        compose.onNodeWithText("Save").performClick()
        verify(exactly = 1) { model.createGroup("Weekend", setOf(Identifier("rules", "One"))) }
        verify(exactly = 0) { model.select(any()) }
    }

    @Test fun addPageExplainsDefaultGroupAndDoesNotContainGroupManagement() {
        activity.get().setContent { MaterialTheme { SourcesScreen(initial, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Manage groups").assertDoesNotExist()
        compose.onNodeWithText("New sources are ungrouped. Organize them after adding.").assertIsDisplayed()
    }

    @Test fun failedSaveKeepsTheNameAndRepeatedSuccessClosesTheEditor() {
        var state by mutableStateOf(initial.copy(message = indi.renakoni.nextvol.R.string.source_groups_saved, groupRevision = 1))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNodeWithText("New group").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Weekend")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { state = state.copy(message = indi.renakoni.nextvol.R.string.sources_action_failed) }
        compose.onNode(hasSetTextAction()).assertTextContains("Weekend")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle { state = state.copy(groups = groups + SourceGroup("weekend", "Weekend"),
            message = indi.renakoni.nextvol.R.string.source_groups_saved, groupRevision = 2) }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNode(hasText("Weekend") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        verify(exactly = 2) { model.createGroup("Weekend", emptySet()) }
    }
}
