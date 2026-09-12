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
        compose.onNodeWithText("Save configuration and permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.saveConfiguration(id, "", "https://books.invalid/\nhttps://cdn.invalid:443") }
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
            compose.onNodeWithText("Approve permissions and apply selected sources").performScrollTo().performClick()
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
