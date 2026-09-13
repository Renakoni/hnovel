package indi.dmzz_yyhyy.lightnovelreader.utils.network

import android.app.Application
import android.net.Uri
import androidx.work.ListenableWorker
import com.github.michaelbull.result.Err
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.utils.DefaultBookCoverRenderer
import indi.dmzz_yyhyy.lightnovelreader.utils.ImageUtils
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ImageDownloaderCoverTest {
    @After fun cleanup() { unmockkAll() }
    @Test fun onlyCoverFailuresHaveAnExportSubstituteAndCancellationStillPropagates() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val book = BookIdentity.book("fixture")
        val dir = File(context.cacheDir, "cover-export-test").apply { mkdirs() }
        val cover = File(dir, "cover.jpg")
        val body = File(dir, "content.jpg")
        val cancelled = File(dir, "cancelled.jpg")
        val uri = Uri.parse("https://fixture.invalid/image")
        mockkObject(ImageUtils)
        coEvery { ImageUtils.uriToBitmap(any(), any(), any(), any()) } returns Err(IOException("fixture"))
        val fallback = DefaultBookCoverRenderer.Text(book.storageKey, "A book", "An author")
        assertEquals(ListenableWorker.Result.success(), ImageDownloader(context, book,
            listOf(ImageDownloader.Task(cover, uri, true, fallback))) { _, _ -> }.run())
        assertTrue(cover.length() > 0)
        assertEquals(ListenableWorker.Result.failure(), ImageDownloader(context, book,
            listOf(ImageDownloader.Task(body, uri))) { _, _ -> }.run())
        assertFalse(body.exists())
        coEvery { ImageUtils.uriToBitmap(any(), any(), any(), any()) } throws CancellationException("cancel")
        try {
            ImageDownloader(context, book, listOf(ImageDownloader.Task(cancelled, uri, true, fallback))) { _, _ -> }.run()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertFalse(cancelled.exists()) }
    }
}
