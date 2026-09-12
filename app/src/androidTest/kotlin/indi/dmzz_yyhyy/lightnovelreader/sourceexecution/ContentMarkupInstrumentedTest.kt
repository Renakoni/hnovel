package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.*
import hnovel.rules.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentMarkupInstrumentedTest {
    @Test fun normalChaptersCrossTheActualIsolatedBinderWithDefaultBudgets() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("markup", "legado", "1")
        val executor = AndroidIsolatedExecutor(InstrumentationRegistry.getInstrumentation().targetContext, authority)
        try {
            for (count in listOf(40, 80, 400)) {
                val result = executor.execute(id, ExecutionTask.ContentMarkup("<p>${"a".repeat(80)}</p>".repeat(count)),
                    ExecutionLimits(maxOutputBytes = 196608))
                assertTrue(result.toString(), result is ExecutionResult.Success)
                val executed = Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
                assertEquals(count, (executed.value as RuleValue.Items).values.size)
            }
        } finally { executor.close() }
    }

    @Test fun deepMarkupFailsWithALocationAndTheWorkerCanServeTheNextSource() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("markup", "legado", "1")
        val executor = AndroidIsolatedExecutor(InstrumentationRegistry.getInstrumentation().targetContext, authority)
        try {
            val failure = executor.execute(id, ExecutionTask.ContentMarkup("<div>".repeat(70))) as ExecutionResult.Failure
            assertEquals(FailureCode.Timeout, failure.code)
            assertEquals(RuleError(RuleStage.Budget, RuleLocation("ruleContent.parts"), "MarkupDepthLimit"), failure.ruleError)
            val next = authority.issue("other-source", "legado", "1")
            assertEquals(ExecutionResult.Success("42"), executor.execute(next, ExecutionTask.Script("21*2")))
        } finally { executor.close() }
    }
}
