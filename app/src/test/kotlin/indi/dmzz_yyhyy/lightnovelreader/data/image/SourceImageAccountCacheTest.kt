package indi.dmzz_yyhyy.lightnovelreader.data.image

import android.app.Application
import coil3.ImageLoader
import coil3.disk.DiskCache
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionManager
import io.nightfish.lightnovelreader.api.identifier.Identifier
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceImageAccountCacheTest {
    @Test fun logoutDeletesRetiredDiskEntriesAndFencesLateWritesWithoutEvictingOtherSources() {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("account-images").toFile()
        val accounts = SourceSessionManager()
        val owner = SourceImageAccountCache(context, accounts)
        val disk = DiskCache.Builder().directory(root.toOkioPath()).maxSizeBytes(1024 * 1024).build()
        val loader = ImageLoader.Builder(context).diskCache(disk).build()
        owner.attach(loader)
        val a = Identifier("rules", "image-A"); val b = Identifier("rules", "image-B")
        fun write(source: Identifier, key: String) {
            owner.remember(source, 0, key)
            owner.commit(source, 0) {
                val editor = disk.openEditor(key)!!
                disk.fileSystem.write(editor.metadata) {}
                disk.fileSystem.write(editor.data) { writeUtf8("synthetic-private-image") }
                editor.commit()
            }
        }
        try {
            write(a, "a-revision-1"); write(a, "a-revision-2"); write(b, "b-revision-1")
            accounts.begin(a)
            owner.purge(a, 0)
            assertNull(disk.openSnapshot("a-revision-1"))
            assertNull(disk.openSnapshot("a-revision-2"))
            disk.openSnapshot("b-revision-1").use { assertNotNull(it) }
            var lateWrite = false
            assertTrue(runCatching { owner.commit(a, 0) { lateWrite = true } }.isFailure)
            assertFalse(lateWrite)
        } finally { loader.shutdown(); disk.shutdown(); root.deleteRecursively() }
    }
}
