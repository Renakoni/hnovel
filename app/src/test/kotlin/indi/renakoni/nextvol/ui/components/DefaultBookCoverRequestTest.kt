package indi.renakoni.nextvol.ui.components

import android.app.Application
import android.os.Looper
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.memory.MemoryCache
import coil3.request.SuccessResult
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DefaultBookCoverRequestTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun repeatedArtworkUsesBoundedMemoryCacheAndRasterizesOffMain() = runBlocking {
        val loader = ImageLoader.Builder(context).memoryCache { MemoryCache.Builder().maxSizeBytes(4 * 1024 * 1024).build() }.build()
        try {
            val request = defaultBookCoverRequest(context, DefaultBookCoverRenderer.Text("book", "A title"), 118, 172)
            @Suppress("UNCHECKED_CAST")
            val factory = request.fetcherFactory!!.first as Fetcher.Factory<DefaultBookCoverRenderer.Text>
            val threads = CopyOnWriteArrayList<Thread>()
            val observed = request.newBuilder().fetcherFactory<DefaultBookCoverRenderer.Text> { data, options, imageLoader ->
                val delegate = factory.create(data, options, imageLoader)!!
                Fetcher { threads += Thread.currentThread(); delegate.fetch() }
            }.build()
            val first = loader.execute(observed) as SuccessResult
            val second = loader.execute(observed) as SuccessResult
            assertEquals(118, first.image.width)
            assertEquals(172, first.image.height)
            assertEquals(DataSource.MEMORY, first.dataSource)
            assertEquals(DataSource.MEMORY_CACHE, second.dataSource)
            assertSame(first.image, second.image)
            assertEquals(1, threads.size)
            assertNotSame(Looper.getMainLooper().thread, threads.single())
            assertTrue(loader.memoryCache!!.size <= loader.memoryCache!!.maxSize)
        } finally { loader.shutdown() }
    }

    @Test fun metadataAndPixelSizesHaveDistinctKeysAndLargeCoversAreCapped() = runBlocking {
        val text = DefaultBookCoverRenderer.Text("book", "FB", "author")
        fun request(value: DefaultBookCoverRenderer.Text = text, width: Int = 118, height: Int = 172) =
            defaultBookCoverRequest(context, value, width, height)
        assertEquals("FB".hashCode(), "Ea".hashCode())
        val requests = listOf(request(), request(text.copy(title = "Ea")), request(text.copy(bookId = "other")),
            request(text.copy(author = "another")), request(width = 94), request(height = 138),
            request(text.copy(bookId = "ab", title = "c")), request(text.copy(bookId = "a", title = "bc")))
        assertEquals(requests.size, requests.map { it.memoryCacheKey }.toSet().size)
        val version = DefaultBookCoverRenderer.STYLE_VERSION
        assertTrue(request().memoryCacheKey!!.startsWith("default-cover:${version.length}:$version"))
        val loader = ImageLoader.Builder(context).build()
        try {
            val large = loader.execute(request(width = 2400, height = 3480)) as SuccessResult
            assertEquals(600, large.image.width)
            assertEquals(870, large.image.height)
        } finally { loader.shutdown() }
    }

    @Test fun remoteFallbackDoesNotRetainArtworkAndDedicatedCacheStaysWithinFourMiB() = runBlocking {
        val loader = DefaultBookCoverImages.get(context)
        val cache = loader.memoryCache!!
        cache.clear()
        assertEquals(4L * 1024 * 1024, cache.maxSize)
        assertNull(loader.diskCache)
        val text = DefaultBookCoverRenderer.Text("remote", "Remote title")
        val fallback = defaultBookCoverRequest(context, text, 600, 870, cacheArtwork = false)
        val first = loader.execute(fallback) as SuccessResult
        assertEquals(DataSource.MEMORY, first.dataSource)
        assertEquals(0L, cache.size)
        assertNull(cache[MemoryCache.Key(fallback.memoryCacheKey!!)])
        assertEquals(DataSource.MEMORY, (loader.execute(fallback) as SuccessResult).dataSource)
        repeat(8) { index ->
            loader.execute(defaultBookCoverRequest(context, text.copy(bookId = "local-$index"), 600, 870))
            assertTrue(cache.size <= cache.maxSize)
        }
        assertTrue(cache.size > 0)
        // Cache writes for real default covers never enter the application's remote image pool.
        assertNotSame(coil3.SingletonImageLoader.get(context).memoryCache, cache)
        cache.clear()
    }
}
