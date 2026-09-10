package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import hnovel.content.RuleTaskRunner
import hnovel.imports.*
import hnovel.network.NetworkGrant
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
class SourceRevisionUpdatesTest {
    private class Host : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val root = Files.createTempDirectory("source-update").toFile()
        override fun getFilesDir() = root
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    private fun candidate(sources: ImportedRuleSources, raw: JsonObject): SourceDefinition {
        val preview = sources.importer.preview(raw.toString())
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        val row = preview.candidates.single()
        val commit = sources.importer.commit(preview, listOf(ImportSelection(0,
            row.existing?.let(ImportDecision::Replace) ?: ImportDecision.Add)))
        assertNull(commit.error)
        assertNull(commit.items.single().error)
        return sources.definitions.list().single()
    }

    @Test fun updateAndRollbackPreserveIdentityRetireOldRuntimeAndRestoreFromOneSnapshot() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val book = fixture.server.url("/book/one").toString()
                assertEquals("Same title", runtime.getBookInformation(book).get()!!.title)
                val next = candidate(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("Updated"))))
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                updates.apply(id, next.reference(), grants)
                assertFalse(runtime.isAvailable)
                val changed = (registry.resolve(id) as SourceResolution.Ready).runtime
                assertEquals(first.sourceId, next.sourceId)
                assertEquals(next.contentDigest, changed.metadata.revision)
                assertEquals(book, changed.getBookInformation(book).get()!!.id)
                updates.rollback(id, grants)
                assertFalse(changed.isAvailable)
                assertEquals(first.contentDigest, sources.installedSources().single().definition.contentDigest)
                sources.stop()
                registry = WebSourceRegistry(fixture.authority)
                sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
                sources.restore()
                assertEquals(first.contentDigest, (registry.resolve(id) as SourceResolution.Ready).runtime.metadata.revision)
                assertEquals(next.contentDigest, sources.installedSources().single().previous!!.contentDigest)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun initializationPermissionAndDurableCommitFailuresLeaveOldRuntimeUsable() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val old = (registry.resolve(id) as SourceResolution.Ready).runtime
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                val invalid = candidate(sources, JsonObject(fixture.raw() + ("jsLib" to JsonPrimitive("throw new Error('private-secret');"))))
                val failure = runCatching { updates.apply(id, invalid.reference(), grants) }.exceptionOrNull() as RevisionException
                assertEquals(RevisionError.InitializationFailed, failure.code)
                assertFalse(failure.toString().contains("private-secret"))
                val next = candidate(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("New"))))
                assertTrue(runCatching { updates.apply(id, next.reference(), listOf(NetworkGrant("https://unapproved.test/"))) }.isFailure)
                val blocked = File(host.filesDir, "rule-sources/active.json.new").apply { mkdirs() }
                File(blocked, "block").writeText("test")
                assertTrue(runCatching { updates.apply(id, next.reference(), grants) }.isFailure)
                assertSame(old, (registry.resolve(id) as SourceResolution.Ready).runtime)
                assertEquals(first.contentDigest, sources.installedSources().single().definition.contentDigest)
                assertEquals("Same title", old.getBookInformation(fixture.server.url("/book/one").toString()).get()!!.title)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun sourceRemovalDuringValidationCannotBeUndoneByLateUpdate() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runner = RuleTaskRunner { identity, task, limits, broker ->
                fixture.runner.execute(identity, task, limits, broker).also { entered.complete(Unit); release.await() }
            }
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val next = candidate(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("New"))))
                val updates = SourceRevisionUpdates(host, sources, accounts, runner, fixture.authority)
                val pending = async { runCatching { updates.apply(id, next.reference(), grants) } }
                withTimeout(5000) { entered.await() }
                sources.remove(id)
                release.complete(Unit)
                assertTrue(pending.await().isFailure)
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                assertTrue(sources.installedSources().isEmpty())
            } finally { release.complete(Unit); sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun downloadCheckOnlyPreviewsAndDownloadFailureKeepsTheInstalledRevision() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                okhttp3.mockwebserver.MockWebServer().use { server ->
                    server.start()
                    val grant = NetworkGrant(server.url("/").toString(), true)
                    hnovel.network.SourceBroker(File(host.cacheDir, "import").toPath()).use { broker ->
                        val session = broker.open(hnovel.network.SourceScope("download", "fixture", LEGADO_PROFILE), listOf(grant))
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(fixture.raw().toString()))
                        val initial = sources.importer.previewUrl(server.url("/sources.json").toString(), session)
                        assertNull(sources.importer.commit(initial, listOf(ImportSelection(0, ImportDecision.Add))).error)
                        val first = sources.definitions.list().single()
                        val id = sources.activate(first.reference(), listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                        val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(JsonObject(fixture.raw() +
                            ("jsLib" to JsonPrimitive("throw new Error('must not execute at check');"))).toString()))
                        val check = updates.check(id, grant)
                        assertFalse(check.unchanged)
                        assertTrue(check.preview.issues.isEmpty())
                        assertEquals(first, sources.installedSources().single().definition)
                        assertEquals(first, sources.definitions.list().single())
                        assertEquals(0, fixture.documents.get())
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
                        assertEquals(ImportCode.DownloadFailed, updates.check(id, grant).preview.issues.single().code)
                        assertEquals(first, sources.installedSources().single().definition)
                    }
                }
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }
}
