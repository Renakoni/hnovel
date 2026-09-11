package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
import hnovel.imports.ImportDecision
import hnovel.imports.ImportSelection
import hnovel.network.NetworkGrant
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourcesViewModelTest {
    @Test fun discoveryLoginSelectsTheOriginAndDoesNotRestartOnRecreation(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("discovery-settings").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val updates = SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority)
            val login = SourceLoginService(sources, accounts)
            val raw = JsonObject(fixture.raw() + mapOf("loginUrl" to JsonPrimitive("function login(){}"),
                "loginUi" to JsonPrimitive("[{\"name\":\"user\"}]")))
            val committed = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
            val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val model = SourcesViewModel(context, sources, updates, login, registry)
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                model.openFromDiscovery(id, true)
                val opened = idle()
                assertEquals(id, opened.selected)
                assertEquals("user", opened.loginForm!!.fields.single().name)
                val generation = accounts.current(id).generation
                assertEquals(1L, generation)
                model.openFromDiscovery(id, true)
                idle()
                assertEquals(generation, accounts.current(id).generation)
                assertEquals(0, fixture.documents.get())
            } finally { model.cancel(); sources.stop(); root.deleteRecursively(); Dispatchers.resetMain() }
        }
    }

    @Test fun previewApprovalUpdateRollbackAndRemovalUseProductionServices(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-management").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val updates = SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority)
            val login = SourceLoginService(sources, accounts)
            val model = SourcesViewModel(context, sources, updates, login, registry)
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonObject(fixture.raw() + ("bookSourceUrl" to JsonPrimitive("https://fixture.invalid/source")))
                model.previewText(raw.toString()); assertEquals(1, idle().preview!!.candidates.size)
                assertTrue(sources.installedSources().isEmpty())
                val permissions = mapOf(0 to "https://fixture.invalid/")
                model.commit(setOf(0), permissions, false)
                model.commit(setOf(0), permissions, false)
                val first = idle().installed.single()
                assertEquals(1, registry.sources.value.size)
                val id = ImportedRuleSources.id(first.definition)
                model.previewText(JsonObject(raw + ("bookSourceName" to JsonPrimitive("New name"))).toString()); idle()
                model.commit(setOf(0), permissions, false)
                val updated = idle().installed.single()
                assertEquals(id, ImportedRuleSources.id(updated.definition))
                assertEquals("New name", updated.definition.displayName)
                model.rollback(id, permissions.getValue(0))
                assertEquals(first.definition.contentDigest, idle().installed.single().definition.contentDigest)
                model.saveConfiguration(id, "saved after rollback", permissions.getValue(0))
                assertEquals(indi.dmzz_yyhyy.lightnovelreader.R.string.sources_saved, idle().message)
                assertEquals(updated.definition.contentDigest, idle().installed.single().previous!!.contentDigest)
                model.select(id)
                assertEquals("saved after rollback", idle().variable)
                model.checkUpdate(id)
                assertEquals(indi.dmzz_yyhyy.lightnovelreader.R.string.sources_no_update_url, idle().message)
                model.remove(id)
                assertTrue(idle().installed.isEmpty())
                assertTrue(registry.sources.value.isEmpty())
                val secondRaw = JsonObject(raw + ("bookSourceUrl" to JsonPrimitive("https://fixture.invalid/second")))
                val batchPermissions = mapOf(0 to "https://fixture.invalid/", 1 to "https://fixture.invalid/")
                model.previewText(JsonArray(listOf(raw, secondRaw)).toString()); idle()
                model.commit(setOf(0, 1), batchPermissions, false)
                val installed = idle().installed
                assertEquals(2, installed.size)
                val second = installed.single { it.definition.importKey != first.definition.importKey }
                val secondId = ImportedRuleSources.id(second.definition)
                val changed = JsonObject(raw + ("bookSourceName" to JsonPrimitive("Batch success")))
                val broken = JsonObject(secondRaw + ("jsLib" to JsonPrimitive("function broken(")))
                model.previewText(JsonArray(listOf(changed, broken)).toString()); idle()
                model.commit(setOf(0, 1), batchPermissions, false)
                val partial = idle()
                assertEquals(indi.dmzz_yyhyy.lightnovelreader.R.string.sources_import_partial, partial.message)
                assertNull(partial.preview)
                assertTrue(partial.installed.any { it.definition.displayName == "Batch success" })
                assertEquals(second.definition, partial.installed.single { ImportedRuleSources.id(it.definition) == secondId }.definition)
                model.saveConfiguration(secondId, "kept after rejected candidate", batchPermissions.getValue(1))
                assertEquals(indi.dmzz_yyhyy.lightnovelreader.R.string.sources_saved, idle().message)
            } finally { model.cancel(); sources.stop(); root.deleteRecursively(); Dispatchers.resetMain() }
        }
    }
}
