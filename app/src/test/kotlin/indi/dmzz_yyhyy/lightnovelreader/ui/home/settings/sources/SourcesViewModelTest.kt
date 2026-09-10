package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
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
            } finally { model.cancel(); sources.stop(); root.deleteRecursively(); Dispatchers.resetMain() }
        }
    }
}
