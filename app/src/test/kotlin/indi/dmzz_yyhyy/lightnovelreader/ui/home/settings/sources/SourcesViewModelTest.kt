package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
import hnovel.imports.ImportDecision
import hnovel.imports.ImportSelection
import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.StorageArea
import hnovel.network.StorageRequest
import hnovel.network.StorageRequestKey
import hnovel.network.NetworkGrant
import io.mockk.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
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
    @Test fun initializationFailureKeepsSettingsOpenWithoutReadingRuntimeAccountData(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-initialization-ui").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        val definition = hnovel.imports.SourceDefinition("broken", "legado", "fixture", "https://fixture.invalid/", "Broken", true,
            false, hnovel.imports.ImportOrigin(hnovel.imports.ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val registry = WebSourceRegistry(hnovel.execution.ExecutionAuthority())
        val source = mockk<io.nightfish.lightnovelreader.api.web.WebBookDataSource>()
        every { source.id } returns id
        every { source.onLoad() } throws IllegalStateException("controlled initialization failure")
        val registration = registry.register(SourceMetadata(io.nightfish.lightnovelreader.api.web.WebDataSourceItem(id, "Broken", "fixture"),
            setOf(SourceCapability.Search, SourceCapability.Login))) { source }
        val sources = mockk<ImportedRuleSources>()
        coEvery { sources.installedSources() } returns listOf(InstalledRuleSource(definition, listOf(NetworkGrant("https://fixture.invalid/")), null))
        val login = mockk<SourceLoginService>(relaxed = true)
        val zLibrary = mockk<ZLibrarySources>()
        every { zLibrary.state } returns MutableStateFlow(indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibraryState())
        val model = SourcesViewModel(context, sources, mockk(), login, registry, zLibrary)
        suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
        try {
            idle()
            assertEquals(SourceStatus.Registered, registry.sources.value.single().status)
            model.select(id)
            assertEquals(id, idle().selected)
            withTimeout(10000) { model.state.first { it.registry.singleOrNull()?.status == SourceStatus.Failed } }
            verify(exactly = 1) { source.onLoad() }
            coVerify(exactly = 0) { sources.loginTarget(any()) }
            coVerify(exactly = 0) { login.status(any()) }
            model.beginLogin(id); idle()
            coVerify(exactly = 0) { login.begin(any()) }
        } finally { model.cancel(); registration.unregister(); Dispatchers.resetMain(); root.deleteRecursively() }
    }

    @Test fun disabledImportsCanBeSelectedAndPermissionsNeverEraseInactiveConfiguration(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-preferences-ui").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val updates = SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority)
            val model = SourcesViewModel(context, sources, updates, SourceLoginService(sources, accounts), registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonObject(fixture.raw() + mapOf("bookSourceUrl" to JsonPrimitive("https://fixture.invalid/"),
                    "enabled" to JsonPrimitive(false)))
                model.previewText(raw.toString()); idle()
                model.commit(setOf(0), mapOf(0 to ""), false)
                val installed = idle().installed.single()
                val id = ImportedRuleSources.id(installed.definition)
                assertFalse(installed.preferences.enabled)
                assertTrue(registry.sources.value.isEmpty())
                model.select(id)
                assertEquals(id, idle().selected)
                assertNull(idle().message)
                model.setEnabled(id, true); idle()
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                model.saveConfiguration(id, null, "https://fixture.invalid/"); idle()
                assertTrue(registry.resolve(id) is SourceResolution.Ready)
                model.saveConfiguration(id, "keep this", "https://fixture.invalid/")
                assertEquals("keep this", idle().variable)
                model.setEnabled(id, false)
                assertEquals("", idle().variable)
                model.saveConfiguration(id, null, ""); idle()
                model.saveConfiguration(id, null, "https://fixture.invalid/"); idle()
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                model.setEnabled(id, true)
                assertEquals("keep this", idle().variable)
                model.saveConfiguration(id, "saved before revocation", ""); idle()
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                model.saveConfiguration(id, null, "https://fixture.invalid/")
                assertEquals("saved before revocation", idle().variable)
                assertEquals(0, fixture.server.requestCount)
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }

    @Test fun dynamicLoginFormBelongsToTheNewAccountAndCancellingItsLoadRetiresTheAttempt(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("dynamic-login").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val login = SourceLoginService(sources, accounts)
            val model = SourcesViewModel(context, sources, SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority), login, registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonObject(fixture.raw() + mapOf("loginUrl" to JsonPrimitive("function login(){}"),
                    "loginUi" to JsonPrimitive("@js:var saved=source.getLoginInfoMap();JSON.stringify([{name:'user',default:saved?saved.get('user'):'new-account'}])")))
                model.previewText(raw.toString(), EXTENSION_PROFILE)
                val preview = idle().preview!!
                assertEquals(EXTENSION_PROFILE, preview.candidates.single().profile)
                val committed = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                sources.loginTarget(id).session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, "{\"user\":\"old-account\"}"))
                model.beginLogin(id)
                assertEquals("new-account", idle().loginForm!!.values["user"])
                model.cancelLogin(); idle()
                val previous = accounts.current(id).generation
                val entered = CompletableDeferred<Unit>()
                fixture.afterRun = { entered.complete(Unit); awaitCancellation() }
                model.beginLogin(id)
                withTimeout(10000) { entered.await() }
                model.cancel()
                withTimeout(10000) { accounts.changes.first { (it[id] ?: 0) >= previous + 2 } }
                assertNull(idle().loginForm)
                assertEquals(LoginStatus.LoggedOut, login.status(id))
                assertEquals(0, fixture.documents.get())
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }

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
            val model = SourcesViewModel(context, sources, updates, login, registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
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
            val model = SourcesViewModel(context, sources, updates, login, registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
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
