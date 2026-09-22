package indi.renakoni.nextvol.benchmark.explore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import indi.renakoni.nextvol.benchmark.ui.UiAutomatorTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class MinifiedSourceRuntimeTest : UiAutomatorTest() {
    @Test
    fun importedCatalogueRunsInTheMinifiedIsolatedWorker() {
        val seeded = shell(
            "am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
                "-a $TARGET_PACKAGE.benchmark.SEED_SOURCE"
        )
        assertTrue(seeded, seeded.contains("source=SUCCEEDED"))
        launchApp()
        clickLastText("Categories")
        clickText("Runtime fixture")
        listOf(
            "JavaScript: 42", "RegExp: 35", "Typed arrays: true", "Continuation: function",
            "Errors: controlled", "Library: Shared", "Bridge: null/hello", "DOM: Chapter", "Isolation: blocked/undefined",
        ).forEach { assertText(it) }

        restartApp()
        clickLastText("Categories")
        clickText("Runtime fixture")
        assertText("DOM: Chapter")
        assertText("Bridge: null/hello")
    }
}
