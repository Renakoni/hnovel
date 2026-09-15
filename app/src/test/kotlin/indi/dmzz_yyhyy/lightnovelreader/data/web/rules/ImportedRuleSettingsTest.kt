package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ImportedRuleSettingsTest {
    @Test fun disabledSourceKeepsStoredSettingsAndAccountFenceAcrossRestart() = runBlocking {
        val root = Files.createTempDirectory("stored-rule-settings").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        // Check that the storage-only path uses the same cipher and exact storage identity.
        val cipher = object : StorageCipher {
            override fun seal(bytes: ByteArray, identity: String) = (identity + "\n").toByteArray() + bytes
            override fun open(bytes: ByteArray, identity: String): ByteArray {
                val prefix = (identity + "\n").toByteArray()
                check(bytes.take(prefix.size).toByteArray().contentEquals(prefix))
                return bytes.copyOfRange(prefix.size, bytes.size)
            }
        }
        RuleSourceFixture().use { fixture ->
            fixture.afterRun = { error("Settings must not run rules") }
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            var sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner, cipher)
            try {
                val preview = sources.importer.preview(fixture.raw().toString())
                val reference = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).items.single().reference!!
                val id = sources.activate(reference, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val session = sources.loginTarget(id).session
                session.write(StorageRequest(StorageArea.Config, "variable", "saved"))
                session.write(StorageRequest(StorageArea.Account, "login/status", "session"))
                session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO,
                    """{"user":"reader","password":"synthetic-secret"}"""))
                assertEquals("reader", sources.storedSettings(id, "user").accountName)
                session.write(StorageRequest(StorageArea.Account, "login/status", "required"))
                assertNull(sources.storedSettings(id, "user").accountName)
                session.write(StorageRequest(StorageArea.Account, "login/status", "session"))
                sources.setPreferences(id, enabled = false)
                assertTrue(session.closed)
                assertEquals(RuleStoredSettings("saved", "session"), sources.storedSettings(id))
                assertEquals(RuleStoredSettings("saved", "session", "reader"), sources.storedSettings(id, "user"))
                sources.saveVariable(id, "edited offline")
                sources.stop()
                sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner, cipher)
                assertEquals(RuleStoredSettings("edited offline", "session"), sources.storedSettings(id))
                assertEquals("reader", sources.storedSettings(id, "user").accountName)
                assertTrue(registry.sources.value.isEmpty())
                val generation = accounts.current(id).generation
                accounts.begin(id)
                assertEquals(RuleStoredSettings("edited offline", null), sources.storedSettings(id))
                assertNull(sources.storedSettings(id, "user").accountName)
                assertEquals(generation + 1, accounts.current(id).generation)
                assertEquals(0, fixture.server.requestCount)
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }

    @Test fun aSourceWithoutGrantsCanEditVariablesWithoutEnablingOrRegistering() = runBlocking {
        val root = Files.createTempDirectory("ungranted-rule-settings").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        RuleSourceFixture().use { fixture ->
            fixture.afterRun = { error("No authority was granted") }
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            try {
                val preview = sources.importer.preview(fixture.raw().toString())
                val reference = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).items.single().reference!!
                val id = sources.activate(reference, emptyList())
                val installed = sources.installedSources().single()
                sources.saveVariable(id, "manual value")
                assertEquals("manual value", sources.storedSettings(id).variable)
                assertEquals(installed, sources.installedSources().single())
                assertTrue(registry.sources.value.isEmpty())
                assertEquals(0L, accounts.current(id).generation)
                sources.remove(id)
                assertTrue(runCatching { sources.saveVariable(id, "stale editor") }.isFailure)
                assertTrue(sources.installedSources().isEmpty())
                assertEquals(0, fixture.server.requestCount)
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }
}
