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
                val raw = JsonObject(fixture.raw() + ("exploreUrl" to JsonPrimitive("@js:infoMap.note='diagnostic';infoMap.save();[{title:'Books',url:'/search'}]")) +
                    ("ruleExplore" to fixture.raw().getValue("ruleSearch")) + ("ruleSearch" to buildJsonObject {
                    put("bookList", "li"); put("bookUrl", "a@href")
                    put("name", "@js:source.put('private','$secret');throw '$secret';")
                }))
                val preview = sources.importer.preview(raw.toString())
                val committed = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val target = sources.loginTarget(id)
                target.session.setCookie(fixture.server.url("/").toString(), "account=$secret")
                target.session.write(StorageRequest(StorageArea.Config, "value:private", "retained"))
                val report = diagnostics.run(id, DiagnosticStage.Search, secret, "", "")
                assertEquals("InvalidRule", report.result)
                assertEquals("ruleSearch.name", report.field)
                assertTrue(report.events.any { it.kind == "network" && it.result == "HTTP_200" })
                assertTrue(report.events.any { it.field == "ruleSearch.name" && it.result != "Success" })
                assertFalse(report.export().contains(secret))
                assertFalse(report.export().contains("http://"))
                assertEquals(StorageResult.Value("retained"), target.session.read(StorageRequest(StorageArea.Config, "value:private")))
                assertTrue(target.session.cookie(fixture.server.url("/").toString()).contains(secret))
                assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("diagnostic-") })
                val discovery = diagnostics.run(id, DiagnosticStage.Discovery, "", "", "", fixture.server.url("/search?q=fixture").toString())
                assertEquals("Success", discovery.result)
                assertTrue(discovery.count > 0)
                assertTrue(discovery.events.any { it.field == "ruleExplore.name" })
                val requests = fixture.documents.get()
                val catalogue = diagnostics.run(id, DiagnosticStage.Discovery, "", "", "")
                assertEquals("Success", catalogue.result)
                assertEquals(1, catalogue.count)
                assertTrue(catalogue.events.any { it.field == "exploreUrl" })
                assertEquals(requests, fixture.documents.get())
                assertEquals(StorageResult.Value(null), target.session.read(StorageRequest(StorageArea.Config, "discovery/info")))
                fixture.status = 503
                val network = diagnostics.run(id, DiagnosticStage.Information, "", fixture.server.url("/book/one").toString(), "")
                assertEquals("Network", network.result)
                assertTrue(network.events.any { it.result == "HTTP_503" })
                val completedHistory = SourceCheckHistory(context).also { it.restore() }.results.value
                assertEquals("Network", completedHistory.getValue(id.id).result)
                assertEquals(target.generation, accounts.current(id).generation)
                fixture.status = 200
                val started = CompletableDeferred<Unit>()
                val original = fixture.server.dispatcher
                fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                        started.complete(Unit)
                        return original.dispatch(request).addHeader("Set-Cookie", "late=diagnostic; Path=/")
                            .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                val pending = launch { diagnostics.run(id, DiagnosticStage.Information, "", fixture.server.url("/book/one").toString(), "") }
                withTimeout(5000) { started.await() }
                pending.cancelAndJoin()
                assertEquals(completedHistory, SourceCheckHistory(context).also { it.restore() }.results.value)
                assertTrue(target.session.cookie(fixture.server.url("/").toString()).contains(secret))
                assertFalse(target.session.cookie(fixture.server.url("/").toString()).contains("late="))
                assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("diagnostic-") })
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }
}
