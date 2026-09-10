package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceDiagnosticsTest {
    @Test fun reportsProductionFailuresWithoutSecretsOrNormalSessionWrites(): Unit = runBlocking {
        val root = Files.createTempDirectory("diagnostics-host").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val diagnostics = SourceDiagnostics(context, sources, fixture.runner, fixture.authority, accounts, registry, StorageCipher.Plain)
            try {
                val secret = "synthetic-secret-never-export"
                val raw = JsonObject(fixture.raw() + ("ruleSearch" to buildJsonObject {
                    put("bookList", "li"); put("bookUrl", "a@href")
                    put("name", "@js:source.put('private','$secret');throw '$secret';")
                }))
                val preview = sources.importer.preview(raw.toString())
                val committed = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val target = sources.loginTarget(id)
                target.session.setCookie(fixture.server.url("/").toString(), "account=$secret")
                target.session.write(StorageRequest(StorageArea.Account, "value:private", "retained"))
                val report = diagnostics.run(id, DiagnosticStage.Search, secret, "", "")
                assertEquals("InvalidRule", report.result)
                assertEquals("ruleSearch.name", report.field)
                assertTrue(report.events.any { it.kind == "network" && it.result == "HTTP_200" })
                assertTrue(report.events.any { it.field == "ruleSearch.name" && it.result != "Success" })
                assertFalse(report.export().contains(secret))
                assertFalse(report.export().contains("http://"))
                assertEquals(StorageResult.Value("retained"), target.session.read(StorageRequest(StorageArea.Account, "value:private")))
                assertTrue(target.session.cookie(fixture.server.url("/").toString()).contains(secret))
                assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("diagnostic-") })
                fixture.status = 503
                val network = diagnostics.run(id, DiagnosticStage.Information, "", fixture.server.url("/book/one").toString(), "")
                assertEquals("Network", network.result)
                assertTrue(network.events.any { it.result == "HTTP_503" })
                assertEquals(target.generation, accounts.current(id).generation)
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }
}
