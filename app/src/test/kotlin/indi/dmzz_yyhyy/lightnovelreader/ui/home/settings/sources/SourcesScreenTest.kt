package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.mockk.mockk
import io.mockk.verify
import hnovel.imports.ImportOrigin
import hnovel.imports.SourceDefinition
import hnovel.imports.EXTENSION_PROFILE
import hnovel.content.LoginField
import hnovel.content.LoginForm
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.ImportedRuleSources
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.InstalledRuleSource
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import org.junit.After
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
class SourcesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val model = mockk<SourcesViewModel>(relaxed = true)
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun longImportKeepsConfirmationReachableBeforeScrollingThroughCandidates() {
        val directory = java.nio.file.Files.createTempDirectory("long-source-preview").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val raw = (0 until 22).joinToString(prefix = "[", postfix = "]") { index ->
                """{"bookSourceUrl":"https://source$index.invalid/","bookSourceName":"Source $index","bookSourceType":0,"customOrder":$index,"extensionFlag":"synthetic-private-value"}"""
            }
            val preview = importer.preview(raw)
            activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(preview = preview), model, onDiagnostics = {}) {} } }
            compose.onNodeWithText("Apply selected").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Select visible").performClick()
            compose.onNodeWithText("22 of 22 selected").assertIsDisplayed()
            verify(exactly = 0) { model.commit(any(), any(), any()) }
            compose.onNodeWithText("Apply selected").performClick()
            verify(exactly = 1) { model.commit((0 until 22).toSet(), (0 until 22).associateWith { "https://source$it.invalid/" }, false) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun filtersKeepSelectionAndPermissionDraftsUntilExplicitConfirmation() {
        val directory = java.nio.file.Files.createTempDirectory("filtered-source-preview").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            fun raw(index: Int) = """{"bookSourceUrl":"https://source$index.invalid/","bookSourceName":"Source $index","bookSourceType":0}"""
            importer.commit(importer.preview(raw(0)), listOf(hnovel.imports.ImportSelection(0, hnovel.imports.ImportDecision.Add)))
            val preview = importer.preview("[${raw(0)},${raw(1)}]")
            activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(preview = preview), model, onDiagnostics = {}) {} } }
            compose.onNodeWithText("Updates").performClick()
            compose.onNodeWithText("Select visible").performClick()
            compose.onNodeWithText("Allowed site origins, one per line").performScrollTo().performTextReplacement("https://approved.invalid/")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("New"))
            compose.onNodeWithText("New").performClick()
            compose.onNodeWithText("Select visible").performClick()
            compose.onNodeWithText("2 of 2 selected").assertIsDisplayed()
            compose.onNodeWithText("Updates").performClick()
            compose.onNodeWithText("https://approved.invalid/").performScrollTo().assertExists()
            verify(exactly = 0) { model.commit(any(), any(), any()) }
            compose.onNodeWithText("Apply selected").performClick()
            verify(exactly = 1) { model.commit(setOf(0, 1), mapOf(0 to "https://approved.invalid/", 1 to "https://source1.invalid/"), false) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun batchSelectionSkipsConflictingDefinitionsAndManualChoiceKeepsOnlyOneVersion() {
        val directory = java.nio.file.Files.createTempDirectory("duplicate-source-preview").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val preview = importer.preview("""[
                {"bookSourceUrl":"https://same.invalid/","bookSourceName":"Variant A","bookSourceType":0},
                {"bookSourceUrl":"https://same.invalid/","bookSourceName":"Variant B","bookSourceType":0},
                {"bookSourceUrl":"https://other.invalid/","bookSourceName":"Other","bookSourceType":0}]
            """)
            activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(preview = preview), model, onDiagnostics = {}) {} } }
            compose.onNodeWithText("Select visible").performClick()
            compose.onNodeWithText("1 of 3 selected").assertIsDisplayed()
            compose.onNodeWithText("Variant A").performScrollTo().performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Variant B"))
            compose.onNodeWithText("Variant B").performClick()
            compose.onNodeWithText("2 of 3 selected").assertIsDisplayed()
            compose.onNodeWithText("Apply selected").performClick()
            verify(exactly = 1) { model.commit(setOf(1, 2), mapOf(1 to "https://same.invalid/", 2 to "https://other.invalid/"), false) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun importNotesShowMeaningAndFieldNamesWithoutSourceValuesOrInternalCodes() {
        val directory = java.nio.file.Files.createTempDirectory("source-preview-notes").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val preview = importer.preview("""{"bookSourceUrl":"https://fixture.invalid/","bookSourceName":"Source","bookSourceType":0,"unknownField":"synthetic-private-value"}""")
            activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(preview = preview), model, onDiagnostics = {}) {} } }
            compose.onNodeWithText("Import notes (2)").performScrollTo().performClick()
            compose.onNodeWithText("Field: unknownField").assertExists()
            compose.onNodeWithText("An unrecognized field is retained. Its behavior is not guaranteed.").assertExists()
            compose.onNodeWithText("ExecutionCompatibilityPending", substring = true).assertDoesNotExist()
            compose.onNodeWithText("synthetic-private-value", substring = true).assertDoesNotExist()
        } finally { directory.deleteRecursively() }
    }

    @Test fun recentCheckKeepsItsStageAndIsMarkedStaleAfterRevisionOrAccountChanges() {
        val definition = SourceDefinition("checked", "legado", "fixture", "https://fixture.invalid/", "Checked", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val summary = indi.dmzz_yyhyy.lightnovelreader.data.web.rules.SourceCheckSummary("old-digest", 3,
            indi.dmzz_yyhyy.lightnovelreader.data.web.rules.DiagnosticStage.Search, "Success", 2, 1789254000000)
        var state by mutableStateOf(SourceManagementState(installed = listOf(InstalledRuleSource(definition,
            listOf(hnovel.network.NetworkGrant("https://fixture.invalid/")), null)), selected = id,
            registry = listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Checked", "fixture"),
                setOf(SourceCapability.Search), revision = "digest", accountGeneration = 4), SourceStatus.Ready)),
            checks = mapOf(definition.sourceId to summary)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        val stale = "Checked before the source or account changed. Check again for a current result."
        compose.onNodeWithText(stale).assertExists()
        compose.runOnIdle { state = state.copy(checks = mapOf(definition.sourceId to summary.copy(revision = "digest"))) }
        compose.onNodeWithText(stale).assertExists()
        compose.runOnIdle { state = state.copy(checks = mapOf(definition.sourceId to summary.copy(revision = "digest", accountGeneration = 4))) }
        compose.onNodeWithText(stale).assertDoesNotExist()
        compose.onNodeWithText("Search · completed", substring = true).assertExists()
        compose.onNodeWithText("Latest anonymous check only", substring = true).assertExists()
    }

    @Test fun enabledSearchOnlySourceDoesNotClaimVerificationOrOfferEmptyConfiguration() {
        val definition = SourceDefinition("unchecked", "legado", "fixture", "https://fixture.invalid/", "Unchecked", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val state = SourceManagementState(installed = listOf(InstalledRuleSource(definition,
            listOf(hnovel.network.NetworkGrant("https://fixture.invalid/")), null)), selected = id,
            registry = listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Unchecked", "fixture"),
                setOf(SourceCapability.Search)), SourceStatus.Ready)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Not checked yet").assertExists()
        compose.onNodeWithText("Available").assertDoesNotExist()
        compose.onNodeWithText("Source configuration").assertDoesNotExist()
        compose.onNodeWithText("Signed out").assertDoesNotExist()
        compose.onNodeWithText("Format: legado").assertDoesNotExist()
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.saveConfiguration(id, null, "https://fixture.invalid/") }
    }

    @Test fun emptyStateOpensExplicitPreviewFlowWithoutImportingAutomatically() {
        activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(), model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Source file URL").performTextInput("https://fixture.invalid/source.json")
        compose.onNodeWithText("Download and preview").performClick()
        verify(exactly = 1) { model.previewUrl("https://fixture.invalid/source.json") }
        verify(exactly = 0) { model.commit(any(), any(), any()) }
        compose.onNodeWithText("Choose local file").assertExists()
        compose.onNodeWithText("Paste source JSON").assertExists()
    }

    @Test fun blockedCoverOriginOnlyChangesTheDraftUntilTheOwnerIsSaved() {
        val definition = SourceDefinition("owner", "legado", "fixture", "https://books.invalid/", "Owner", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val state = SourceManagementState(installed = listOf(InstalledRuleSource(definition,
            listOf(hnovel.network.NetworkGrant("https://books.invalid/")), null,
            listOf(hnovel.network.OriginDenial("https://cdn.invalid:443", hnovel.network.ResourceKind.Image)))), selected = id,
            registry = listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Owner", "fixture"),
                setOf(SourceCapability.Search)), SourceStatus.Ready)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("https://cdn.invalid:443").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cover or content image").assertExists()
        compose.onNodeWithText("Add to permission draft").performScrollTo().performClick()
        verify(exactly = 0) { model.saveConfiguration(any(), any(), any()) }
        compose.onNodeWithText("In permission draft").assertIsNotEnabled()
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.saveConfiguration(id, null, "https://books.invalid/\nhttps://cdn.invalid:443") }
    }

    @Test fun importedSearchOnlySourceHasAnExplicitSearchEntryWithItsIdentity() {
        val definition = SourceDefinition("search-only", "legado", "fixture", "https://fixture.invalid/", "Search only", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val entry = SourceListing(SourceMetadata(WebDataSourceItem(id, "Search only", "fixture"), setOf(SourceCapability.Search)), SourceStatus.Ready)
        var state by mutableStateOf(SourceManagementState(
            installed = listOf(InstalledRuleSource(definition, emptyList(), null)), registry = listOf(entry), selected = id))
        var selected: Identifier? = null
        activity.get().setContent { MaterialTheme {
            SourcesScreen(state, model, onDiagnostics = {}, onSearch = { selected = it }) {}
        } }
        compose.onNodeWithText("Search this source").performClick()
        org.junit.Assert.assertEquals(id, selected)
        compose.runOnIdle { state = state.copy(registry = listOf(entry.copy(metadata = entry.metadata.copy(capabilities = emptySet())))) }
        compose.onNodeWithText("Search this source").assertDoesNotExist()
    }

    @Test fun disabledDefinitionIsSelectableInPreviewWithoutBeingEnabled() {
        val directory = java.nio.file.Files.createTempDirectory("disabled-preview").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val preview = importer.preview("""{"bookSourceUrl":"https://fixture.invalid/","bookSourceName":"Disabled source","bookSourceType":0,"enabled":false}""")
            org.junit.Assert.assertEquals(1, preview.candidates.size)
            activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(preview = preview), model, onDiagnostics = {}) {} } }
            compose.onNode(isToggleable()).assertIsEnabled().assertIsOff().performClick()
            compose.onNodeWithText("Apply selected").performClick()
            verify(exactly = 1) { model.commit(setOf(0), mapOf(0 to "https://fixture.invalid/"), false) }
            verify(exactly = 0) { model.setEnabled(any(), any()) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun preferenceSwitchesAreIndependentAndDoNotDiscardPermissionDrafts() {
        val definition = SourceDefinition("disabled", "legado", "fixture", "https://fixture.invalid/", "Disabled", false,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, """{"exploreUrl":"All::/books"}""")
        val id = ImportedRuleSources.id(definition)
        val installed = InstalledRuleSource(definition, listOf(hnovel.network.NetworkGrant("https://fixture.invalid/")), null)
        var state by mutableStateOf(SourceManagementState(installed = listOf(installed), selected = id))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithContentDescription("Enable source").assertIsOff()
        compose.onNodeWithText("Disabled by the source definition").assertExists()
        compose.onNodeWithText("Allowed site origins, one per line").performScrollTo().performTextReplacement("https://draft.invalid/")
        compose.onNodeWithContentDescription("Show in Explore and Categories").performScrollTo().assertIsOff().performClick()
        verify(exactly = 1) { model.setDiscoveryVisible(id, true) }
        verify(exactly = 0) { model.setEnabled(any(), any()) }
        compose.runOnIdle { state = state.copy(installed = listOf(installed.copy(preferences = installed.preferences.copy(discoveryVisible = true)))) }
        compose.onNodeWithText("https://draft.invalid/").performScrollTo().assertExists()
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.saveConfiguration(id, null, "https://draft.invalid/") }
        compose.onNodeWithContentDescription("Enable source").performScrollTo().performClick()
        verify(exactly = 1) { model.setEnabled(id, true) }
        compose.runOnIdle { state = state.copy(installed = listOf(installed.copy(definition = definition.copy(rawJson = "{}")))) }
        compose.onNodeWithContentDescription("Show in Explore and Categories").assertIsNotEnabled()
        compose.onNodeWithText("This source does not declare a discovery catalogue.").assertExists()
    }

    @Test fun extensionModeIsPassedToTheImportPreview() {
        activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(), model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Extended Legado source").performScrollTo().performClick()
        compose.onNodeWithText("Source file URL").performScrollTo().performTextInput("https://fixture.invalid/extended.json")
        compose.onNodeWithText("Download and preview").performScrollTo().performClick()
        verify(exactly = 1) { model.previewUrl("https://fixture.invalid/extended.json", EXTENSION_PROFILE) }
        verify(exactly = 0) { model.commit(any(), any(), any()) }
    }

    @Test fun zlibraryHasAnAppSearchEntryAndMirrorChangesStayDraftUntilSaved() {
        val native = indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySources
        val settings = indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySettings()
        val state = SourceManagementState(selected = native.ID,
            zLibrary = indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibraryState(settings))
        var selected: Identifier? = null
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}, onSearch = { selected = it }) {} } }
        compose.onNodeWithText("Search this source").performClick()
        org.junit.Assert.assertEquals(native.ID, selected)
        compose.onNodeWithText("https://z-lib.fo:443").performScrollTo().performClick()
        verify(exactly = 0) { model.saveZLibrary(any(), any()) }
        compose.onNodeWithText("Save site and permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.saveZLibrary("https://z-lib.fo:443", settings.origins.joinToString("\n")) }
        compose.onNodeWithContentDescription("Enable source").performScrollTo().performClick()
        verify(exactly = 1) { model.setZLibraryEnabled(false) }
    }

    @Test fun loginControlsSubmitDefaultsAndTheCurrentSourceFormValues() {
        val form = LoginForm(listOf(LoginField("user", "text", label = "Account"), LoginField("password", "password"),
            LoginField("region", "select", choices = listOf("east", "west"), label = "Region"),
            LoginField("remember", "toggle", choices = listOf("no", "yes"), label = "Remember")), null,
            mapOf("user" to "alice", "password" to "", "region" to "east", "remember" to "no"))
        var submitted: Map<String, String>? = null
        activity.get().setContent { MaterialTheme { SourceLoginDialog(form, false, { values, action ->
            org.junit.Assert.assertNull(action); submitted = values
        }, {}) } }
        compose.onNodeWithText("Account").performTextReplacement("carol")
        compose.onNodeWithText("Region: east").performScrollTo().performClick()
        compose.onNodeWithText("west").performClick()
        compose.onNodeWithText("Remember: no").performScrollTo().performClick()
        compose.onAllNodesWithText("Sign in").filter(hasClickAction()).onFirst().performClick()
        org.junit.Assert.assertEquals(mapOf("user" to "carol", "password" to "", "region" to "west", "remember" to "yes"), submitted)
    }
}
