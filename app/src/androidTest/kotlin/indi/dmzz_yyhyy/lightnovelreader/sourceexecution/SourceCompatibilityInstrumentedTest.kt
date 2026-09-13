package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.content.*
import hnovel.execution.*
import hnovel.imports.*
import hnovel.network.SourceBroker
import hnovel.network.SourceScope
import hnovel.rules.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceCompatibilityInstrumentedTest {
    @Test fun binderPreservesTheMissingLibraryBindingAndTheNextInvocationRecovers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = ExecutionAuthority()
        val identity = authority.issue("fixture", LEGADO_PROFILE, "1")
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            val failure = executor.execute(identity, ExecutionTask.Rule("@js:42", RuleValue.Empty,
                location = RuleLocation("searchUrl"), libraryCode = "new JavaImporter()")) as ExecutionResult.Failure
            assertEquals(FailureCode.UnsupportedDependency, failure.code)
            assertEquals(ScriptDependency.JavaImporter, failure.dependency)
            assertEquals(RuleLocation("jsLib"), failure.ruleError!!.location)
            assertEquals(ExecutionResult.Success("\"fixture-prelude\""), executor.execute(identity,
                ExecutionTask.Script("source.loginUrl", sourceLoginUrl = "fixture-prelude")))
        } finally { executor.close() }
    }

    /** Explicit private-fixture opt-in. The original definition/credentials are never APK resources. */
    @Test fun originalComplexSourceReportsItsActualDependencyAtEachEntryPoint() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("complexSourceFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(context.cacheDir, "complex-source-fixture.json")
        val bytes = original.readBytes()
        assertEquals("14dce10e4d799afdd910ee67ea14cb90491660d45be7e8c1e136877540c980f6",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val directory = File(context.cacheDir, "complex-source-check-${UUID.randomUUID()}")
        val store = SourceDefinitionStore(File(directory, "definitions").toPath())
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview(bytes.toString(Charsets.UTF_8), EXTENSION_PROFILE)
        assertTrue(preview.issues.isEmpty())
        assertNull(importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error)
        val definition = store.list().single()
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val broker = SourceBroker(File(directory, "broker").toPath())
        val session = broker.open(SourceScope("complex-fixture", definition.sourceId, definition.profile), emptyList())
        val identity = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "complex-fixture")
        val source = RuleSource(definition, identity, authority, session,
            RuleTaskRunner { owner, task, limits, bridge -> executor.execute(owner, task, limits, bridge) })
        try {
            for (operation in listOf<suspend () -> Any>(
                { source.search("fixture") }, { source.openDiscovery("fixture").catalog() }, { source.loginForm() }
            )) {
                val failure = runCatching { operation() }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.UnsupportedDependency, failure.code)
                assertEquals(ScriptDependency.JavaImporter, failure.dependency)
                assertEquals("jsLib", failure.field)
            }
        } finally {
            source.close(); executor.close(); broker.close(); directory.deleteRecursively()
        }
    }
}
