package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.app.Application
import hnovel.network.SourceScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class NativeBrowserRetentionTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = SourceScope("test", "one", "legado")
    private val values = mapOf("https://example.org:443" to mapOf("appearance" to "dark"))
    private fun file(root: File) = File(root, "source-browser-retention/${nativeBrowserProfile(scope)}.json")

    @Test fun persistedHandoffIsSourceOwnedAndConsumedPayloadDoesNotReturn() {
        val root = temp.newFolder()
        val store = NativeBrowserRetention(root, scope)
        assertNull(store.read())
        store.write(RetainedLocalStorage(1, values))
        assertEquals(RetainedLocalStorage(1, values), NativeBrowserRetention(root, scope.copy(accountGeneration = 1)).read())
        for (other in listOf(scope.copy(namespace = "other"), scope.copy(sourceId = "two"), scope.copy(profile = "other")))
            assertNull(NativeBrowserRetention(root, other).read())
        store.write(RetainedLocalStorage(1))
        assertEquals(RetainedLocalStorage(1), NativeBrowserRetention(root, scope).read())
    }

    @Test @Config(sdk = [30]) fun failedAtomicReplacementKeepsThePriorHandoffReadable() {
        val root = temp.newFolder()
        val store = NativeBrowserRetention(root, scope)
        store.write(RetainedLocalStorage(1, values))
        val collision = File(file(root).path + ".new").apply { mkdir() }
        File(collision, "fixture").writeText("block replacement")
        assertThrows(Exception::class.java) { store.write(RetainedLocalStorage(2)) }
        assertEquals(RetainedLocalStorage(1, values), store.read())
    }

    @Test fun corruptOrOversizedHandoffsAreReportedInsteadOfImported() {
        val root = temp.newFolder()
        val store = NativeBrowserRetention(root, scope)
        store.write(RetainedLocalStorage(1, values))
        for (invalid in listOf("not-json", "{\"generation\":0}", "{\"generation\":1,\"values\":{\"file:///tmp\":{\"x\":\"y\"}}}",
            "x".repeat(NativeBrowserRetention.MAX_BYTES + 1))) {
            file(root).writeText(invalid)
            assertThrows(Exception::class.java) { store.read() }
        }
    }
}
