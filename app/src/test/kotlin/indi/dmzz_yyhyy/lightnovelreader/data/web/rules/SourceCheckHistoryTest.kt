package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import android.content.ContextWrapper
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
class SourceCheckHistoryTest {
    @Test fun latestStageSurvivesRestartWithoutKeepingInputsOrMixingSources(): Unit = runBlocking {
        val root = Files.createTempDirectory("source-check-history").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        try {
            val history = SourceCheckHistory(context)
            val report = SourceDiagnosticReport("source-a", "profile", "revision-a", 3,
                DiagnosticStage.Search, "Success", "private-field-not-retained", 2, emptyList(), false)
            history.record(report, false)
            history.record(report.copy(sourceId = "source-b", result = "Network"), false)
            history.record(report.copy(stage = DiagnosticStage.Discovery, count = 12), true)
            val restored = SourceCheckHistory(context)
            restored.restore()
            assertEquals(history.results.value, restored.results.value)
            val latest = restored.results.value.getValue("source-a")
            assertEquals(DiagnosticStage.Discovery, latest.stage)
            assertTrue(latest.discoveryPage)
            assertEquals("revision-a", latest.revision)
            assertEquals(3L, latest.accountGeneration)
            assertEquals(12, latest.count)
            assertTrue(latest.checkedAtMillis > 0)
            assertEquals("Network", restored.results.value.getValue("source-b").result)
            assertFalse(root.resolve("source-checks.json").readText().contains("private-field-not-retained"))
        } finally { root.deleteRecursively() }
    }
}
