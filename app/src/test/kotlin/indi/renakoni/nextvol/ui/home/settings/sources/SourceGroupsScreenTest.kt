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
    private fun source(id: String, vararg groups: String): InstalledRuleSource {
        val definition = SourceDefinition(id, "legado", LEGADO_PROFILE, "https://$id.invalid/", id,
            true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        return InstalledRuleSource(definition, emptyList(), null, preferences = SourcePreferences(true, false, groupIds = groups.toSet()))
    }
    private val initial = SourceManagementState(groups = groups, installed = listOf(source("One"), source("Two", "work")))
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun groupOption(name: String) {
        compose.onNodeWithContentDescription("Group options").performClick()
        compose.onNodeWithText(name).performClick()
    }

    @Test fun managementOnlyOffersActualGroupsNotTheEntireCatalog() {
        activity.get().setContent { MaterialTheme { SourcesScreen(initial.copy(groups = emptyList(),
            installed = listOf(source("One"))), model, onDiagnostics = {}) {} } }
        compose.onNode(hasText("Ungrouped") and isSelectable()).assertIsDisplayed()
        compose.onAllNodesWithText("All").assertCountEquals(1)
        compose.onNodeWithText("All ▾").assertDoesNotExist()
        indi.renakoni.nextvol.data.web.SourceCategory.entries.forEach { category ->
            compose.onNodeWithText(activity.get().getString(category.title)).assertDoesNotExist()
        }
    }

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

    @Test fun batchSelectionSurvivesFilteringAndUpdatesOnlyChosenGroups() {
        var state by mutableStateOf(initial.copy(message = indi.renakoni.nextvol.R.string.source_groups_saved, groupRevision = 1))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Select sources").performScrollTo().performClick()
        compose.onNodeWithText("One").performScrollTo().performClick()
        compose.onNodeWithText("Selected: 1").assertIsDisplayed()
        compose.onNodeWithText("Work").performScrollTo().performClick()
        compose.onNodeWithText("One").assertDoesNotExist()
        compose.onNodeWithText("Two").performScrollTo().performClick()
        compose.onNodeWithText("Selected: 2").assertIsDisplayed()
        compose.onNodeWithText("Edit groups").performClick()
        compose.onNodeWithText("Some selected sources").assertIsDisplayed()
        compose.onNode(hasText("Empty") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Save").performClick()
        verify(exactly = 1) { model.updateGroups(setOf(Identifier("rules", "One"), Identifier("rules", "Two")), setOf("empty"), emptySet()) }
        verify(exactly = 0) { model.setEnabled(any(), any()) }
        compose.runOnIdle { state = state.copy(message = indi.renakoni.nextvol.R.string.source_groups_saved,
            groupRevision = 2,
            installed = state.installed.map { it.copy(preferences = it.preferences.copy(groupIds = it.preferences.groupIds + "empty")) }) }
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
        compose.onNodeWithText("Work").performClick()
        groupOption("Delete")
        compose.onNodeWithText("Only this group will be deleted. Sources, their other groups, and reading data will be kept.").assertIsDisplayed()
        verify(exactly = 0) { model.deleteGroup(any()) }
        compose.onNodeWithText("Cancel").performClick()
        verify(exactly = 0) { model.deleteGroup(any()) }
        groupOption("Delete")
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
        compose.onNodeWithText("Catalog sources are grouped by category. Sources imported from a link or file start ungrouped.").assertIsDisplayed()
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
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Weekend").assertIsDisplayed()
        verify(exactly = 2) { model.createGroup("Weekend", emptySet()) }
    }

    @Test fun managementOmitsUngroupedAndRemovalOnlyChangesTheCurrentGroup() {
        var state by mutableStateOf(initial)
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Ungrouped").assertDoesNotExist()
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("One").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Two").performClick()
        compose.onNodeWithText("Selected: 1").assertIsDisplayed()
        compose.onNodeWithText("Remove from group").performClick()
        verify(exactly = 1) { model.updateGroups(setOf(Identifier("rules", "Two")), emptySet(), setOf("work")) }
        compose.runOnIdle { state = state.copy(groupRevision = 1,
            installed = listOf(source("One"), source("Two"))) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Two").assertDoesNotExist()
        compose.onNodeWithText("No sources in this group yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Ungrouped").assertDoesNotExist()
        verify(exactly = 0) { model.remove(any()) }
        verify(exactly = 0) { model.setEnabled(any(), any()) }
    }

    @Test fun addingSourcesSearchesAcrossGroupsAndKeepsSelectionOnFailure() {
        var state by mutableStateOf(initial.copy(installed = initial.installed + source("Three", "empty")))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("Choose sources").performClick()
        compose.onNodeWithText("Two").assertDoesNotExist()
        compose.onNodeWithText("Add to group").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("one.invalid")
        compose.onNodeWithText("One").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Three")
        compose.onNode(hasText("Three") and isToggleable()).performClick()
        compose.onNodeWithText("Selected: 2").assertIsDisplayed()
        compose.onNodeWithText("Add to group").performClick()
        verify(exactly = 1) { model.updateGroups(
            setOf(Identifier("rules", "One"), Identifier("rules", "Three")), setOf("work"), emptySet()) }
        compose.runOnIdle { state = state.copy(message = indi.renakoni.nextvol.R.string.sources_action_failed) }
        compose.onNodeWithText("Selected: 2").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Three")
        compose.onNodeWithText("Add to group").assertIsEnabled().performClick()
        compose.runOnIdle { state = state.copy(groupRevision = 1, message = null,
            installed = state.installed.map { it.copy(preferences = it.preferences.copy(groupIds = it.preferences.groupIds + "work")) }) }
        compose.onNodeWithText("Add to group").assertDoesNotExist()
        compose.onNodeWithText("One").assertIsDisplayed()
        compose.onNodeWithText("Two").assertIsDisplayed()
        compose.onNodeWithText("Three").assertIsDisplayed()
        verify(exactly = 2) { model.updateGroups(any(), setOf("work"), emptySet()) }
    }

    @Test fun cancellingAddDoesNotChangeGroupsAndDeletingReturnsToOverview() {
        var state by mutableStateOf(initial)
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("Choose sources").performClick()
        compose.onNodeWithText("One").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("One").assertDoesNotExist()
        compose.onNodeWithText("Two").assertIsDisplayed()
        compose.onNodeWithText("Selected: 1").assertDoesNotExist()
        verify(exactly = 0) { model.updateGroups(any(), any(), any()) }
        groupOption("Delete")
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { state = state.copy(groups = groups.filterNot { it.id == "work" },
            installed = listOf(source("One"), source("Two")), groupRevision = 1) }
        compose.onNodeWithText("Manage groups").assertIsDisplayed()
        compose.onNodeWithText("Work").assertDoesNotExist()
        compose.onNodeWithText("Ungrouped").assertDoesNotExist()
    }

    @Test fun renameInTheMemberPagePreservesMembershipAndUngroupedCannotBeDeleted() {
        var state by mutableStateOf(initial)
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNodeWithText("Work").performClick()
        groupOption("Rename group")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Reading")
        compose.onNodeWithText("Save").performClick()
        verify(exactly = 1) { model.renameGroup("work", "Reading") }
        compose.runOnIdle { state = state.copy(groups = listOf(SourceGroup("work", "Reading"), groups[1]),
            groupRevision = 1) }
        compose.onNodeWithText("Reading").assertIsDisplayed()
        compose.onNodeWithText("Two").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Ungrouped").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete group Ungrouped").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rename Ungrouped").assertDoesNotExist()
        verify(exactly = 0) { model.updateGroups(any(), any(), any()) }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun anEmptyGroupSupportsAddingSourcesAtLargeFontSize() {
        activity.get().setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                SourcesScreen(initial, model, onDiagnostics = {}) {}
            }
        } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNodeWithText("Empty").performScrollTo().performClick()
        compose.onNodeWithText("Choose sources").assertIsEnabled().performClick()
        compose.onNodeWithText("One").performScrollTo().performClick()
        compose.onNodeWithText("Add to group").assertIsDisplayed().performClick()
        verify(exactly = 1) { model.updateGroups(setOf(Identifier("rules", "One")), setOf("empty"), emptySet()) }
    }

    @Test fun returningFromAddRestoresSearchAndRemovingTheLastMatchShowsAnEmptyGroup() {
        var state by mutableStateOf(initial)
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("missing")
        compose.onNodeWithText("No matching sources").assertIsDisplayed()
        compose.onNodeWithText("Select").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Two")
        compose.onNodeWithText("Choose sources").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Search sources")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Two")
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Selected: 1").assertIsDisplayed()
        compose.onNodeWithText("Remove from group").performClick()
        compose.runOnIdle { state = state.copy(groupRevision = 1,
            installed = listOf(source("One"), source("Two"))) }
        compose.onNodeWithText("No sources in this group yet").assertIsDisplayed()
        compose.onNodeWithText("No matching sources").assertDoesNotExist()
    }

    @Test fun aSourceCanHaveTwoCheckedGroupsAndOnlyUncheckingOneIsSaved() {
        activity.get().setContent { MaterialTheme { SourcesScreen(initial.copy(
            installed = listOf(source("One", "work", "empty"))), model, onDiagnostics = {}) {} } }
        compose.onNodeWithContentDescription("Change group for One").performScrollTo().performClick()
        compose.onNode(hasText("Work") and isToggleable()).assertIsOn().performClick()
        compose.onNode(hasText("Empty") and isToggleable()).assertIsOn()
        compose.onNode(hasText("Ungrouped") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        verify(exactly = 1) { model.updateGroups(setOf(Identifier("rules", "One")), emptySet(), setOf("work")) }
    }

    @Test fun creatingGroupSavesPendingAdditionsAndRemovalsAndKeepsDraftOnFailure() {
        var state by mutableStateOf(initial.copy(installed = listOf(source("One", "work"), source("Two", "work"))))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithContentDescription("Change group for One").performScrollTo().performClick()
        compose.onNode(hasText("Work") and isToggleable()).assertIsOn().performClick()
        compose.onNode(hasText("Empty") and isToggleable()).assertIsOff().performClick()
        compose.onNodeWithText("New group").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasText("Work") and isToggleable()).assertIsOff()
        compose.onNode(hasText("Empty") and isToggleable()).assertIsOn()
        compose.onNodeWithText("New group").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Weekend")
        compose.onNodeWithText("Save").performClick()
        verify(exactly = 1) { model.createGroup("Weekend", setOf(Identifier("rules", "One")), setOf("empty"), setOf("work")) }
        verify(exactly = 0) { model.updateGroups(any(), any(), any()) }
        compose.runOnIdle { state = state.copy(message = indi.renakoni.nextvol.R.string.sources_action_failed) }
        compose.onNode(hasSetTextAction()).assertTextContains("Weekend")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        verify(exactly = 2) { model.createGroup("Weekend", setOf(Identifier("rules", "One")), setOf("empty"), setOf("work")) }
        compose.runOnIdle { state = state.copy(groups = groups + SourceGroup("weekend", "Weekend"),
            installed = listOf(source("One", "empty", "weekend"), source("Two", "work")),
            message = indi.renakoni.nextvol.R.string.source_groups_saved, groupRevision = 1) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun longPressAndSelectAllRespectTheSearchAndBackLeavesTheGroupOpen() {
        activity.get().setContent { MaterialTheme { SourcesScreen(initial.copy(installed = listOf(
            source("One", "work"), source("Two", "work"))), model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Manage groups").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("One").performTouchInput { longClick() }
        compose.onNodeWithText("Selected: 1").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("two.invalid")
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Selected: 2").assertIsDisplayed()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Selected: 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Selected: 1").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        verify(exactly = 0) { model.updateGroups(any(), any(), any()) }
    }
}
