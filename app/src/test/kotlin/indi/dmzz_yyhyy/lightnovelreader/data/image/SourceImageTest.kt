package indi.dmzz_yyhyy.lightnovelreader.data.image

import android.app.Application
import android.graphics.Bitmap
import coil3.asImage
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceImageTest {
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")
    private val url = "https://fixture.invalid/shared.png"

    @Test fun coilMemoryCacheAndHeadersFollowTheBookAcrossSources() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val registry = WebSourceRegistry()
        var aHeaders = mapOf("Authorization" to "account-a")
        for (book in listOf(a, b)) {
            val source = mockk<WebBookDataSource>(relaxed = true) {
                every { id } returns book.sourceId
                every { cache } returns null
                every { permits } returns 2
                every { imageHeader } answers { if (book == a) aHeaders else mapOf("Authorization" to "account-b") }
            }
            registry.register(source, SourceMetadata(WebDataSourceItem(book.sourceId, "Same name", "fixture"), emptySet()))
        }
        val fetched = mutableListOf<String?>()
        val loader = ImageLoader.Builder(context).components {
            add(SourceImageInterceptor(registry, context))
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                    check(options.networkCachePolicy.readEnabled) { "No disk entry in this fixture" }
                    fetched.add(options.httpHeaders["Authorization"])
                    ImageFetchResult(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.NETWORK)
                }
            })
        }.build()
        suspend fun load(book: SourceBookId) = loader.execute(ImageRequest.Builder(context)
            .data(SourceImage(book, url)).size(2, 2).build())
        try {
            assertTrue(load(a) is SuccessResult)
            assertTrue(load(b) is SuccessResult)
            assertEquals(DataSource.MEMORY_CACHE, (load(a) as SuccessResult).dataSource)
            assertEquals(listOf("account-a", "account-b"), fetched)
            aHeaders = mapOf("Authorization" to "account-a-new")
            assertTrue(load(a) is SuccessResult)
            assertEquals(listOf("account-a", "account-b", "account-a-new"), fetched)
            registry.unregister(a.sourceId)
            assertEquals(DataSource.MEMORY_CACHE, (load(a) as SuccessResult).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, (load(b) as SuccessResult).dataSource)
            assertEquals(3, fetched.size)
            loader.memoryCache?.clear()
            assertFalse(load(a) is SuccessResult)
            assertEquals(3, fetched.size)
        } finally {
            loader.shutdown()
            registry.unregister(a.sourceId)
            registry.unregister(b.sourceId)
        }
    }

    @Test fun revisionAccountAndHeadersAreExecutionIdentityNotBookIdentity() {
        val image = SourceImage(a, url)
        val key = sourceImageCacheKey(image, "1", 0, mapOf("Cookie" to "secret"))
        assertNotEquals(key, sourceImageCacheKey(image, "2", 0, mapOf("Cookie" to "secret")))
        assertNotEquals(key, sourceImageCacheKey(image, "1", 1, mapOf("Cookie" to "secret")))
        assertNotEquals(key, sourceImageCacheKey(image, "1", 0, mapOf("Cookie" to "changed")))
        assertFalse(key.contains("secret"))
        assertFalse(key.contains(url))
        assertEquals(a, SourceBookId.fromStorageKey(a.storageKey))
    }
}
