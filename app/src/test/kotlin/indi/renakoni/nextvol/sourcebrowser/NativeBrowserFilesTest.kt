package indi.renakoni.nextvol.sourcebrowser

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.webkit.WebViewFeature
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeBrowserFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun fallbackProvider() {
        mockkStatic(WebViewFeature::class)
        every { WebViewFeature.isStartupFeatureSupported(any(), any()) } returns false
    }
    @After fun releaseProvider() { unmockkStatic(WebViewFeature::class) }

    private fun context(root: File) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getNoBackupFilesDir() = File(root, "no-backup")
        override fun getCacheDir() = File(root, "cache")
        override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply { dataDir = root.path }
    }

    @Test fun stoppedProfilesAreMovedWithTheirOwnerAndOnlyTheRetiredProfileIsCleared() {
        val root = temp.newFolder()
        val files = NativeBrowserFiles(context(root))
        val a = "a".repeat(64); val b = "b".repeat(64)
        val active = File(root, "app_webview_source_native")
        files.prepare(a)
        active.mkdirs(); File(active, "fixture").writeText("alice")
        files.prepare(b)
        active.mkdirs(); File(active, "fixture").writeText("bob")
        files.prepare(a)
        assertEquals("alice", File(active, "fixture").readText())
        assertEquals("bob", File(files.directory(b), "webview/fixture").readText())
        files.clear(b)
        assertFalse(files.exists(b))
        assertEquals("alice", File(active, "fixture").readText())
        files.clear(a)
        assertFalse(active.exists())
    }

    @Test fun failedParkingDoesNotReassignTheActiveOwnerOrOverwriteItsData() {
        val root = temp.newFolder()
        val files = NativeBrowserFiles(context(root))
        val a = "a".repeat(64); val b = "b".repeat(64)
        val active = File(root, "app_webview_source_native")
        files.prepare(a)
        active.mkdirs(); File(active, "fixture").writeText("alice")
        File(files.directory(a), "webview").mkdirs()
        assertThrows(IllegalStateException::class.java) { files.prepare(b) }
        assertEquals(a, File(root, "no-backup/source-browser/active-owner").readText())
        assertEquals("alice", File(active, "fixture").readText())
        assertFalse(files.directory(b).exists())
    }
}
