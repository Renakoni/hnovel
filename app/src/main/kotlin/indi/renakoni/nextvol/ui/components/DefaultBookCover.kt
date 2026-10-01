package indi.renakoni.nextvol.ui.components

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.core.os.trace
import coil3.asImage
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.memory.MemoryCache
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import kotlinx.coroutines.Dispatchers

@Composable
fun DefaultBookCover(title: String, width: Dp, height: Dp, bookId: String = "", author: String = "",
    cacheArtwork: Boolean = true, imageLoader: ImageLoader = DefaultBookCoverImages.get(LocalContext.current)) {
    val context = LocalContext.current
    val displayTitle = title.trim().ifBlank { stringResource(R.string.cover_untitled) }
    val description = stringResource(R.string.cover_description, displayTitle)
    val text = remember(bookId, displayTitle, author) { DefaultBookCoverRenderer.Text(bookId, displayTitle, author) }
    val pixelWidth = with(LocalDensity.current) { width.roundToPx() }
    val pixelHeight = with(LocalDensity.current) { height.roundToPx() }
    val request = remember(context, text, pixelWidth, pixelHeight, cacheArtwork) {
        defaultBookCoverRequest(context, text, pixelWidth, pixelHeight, cacheArtwork)
    }
    val placeholder = remember(context, text) { ColorPainter(Color(DefaultBookCoverRenderer.backgroundColor(context, text))) }
    // Static artwork becomes one texture; alpha transitions no longer compile programs for each
    // text/stroke primitive. Artwork has a small cache separate from remote cover images.
    AsyncImage(model = request, imageLoader = imageLoader,
        contentDescription = description, modifier = Modifier.size(width, height),
        placeholder = placeholder, error = placeholder, contentScale = ContentScale.FillBounds)
}

internal object DefaultBookCoverImages {
    private var loader: ImageLoader? = null

    @Synchronized
    fun get(context: Context): ImageLoader = loader ?: ImageLoader.Builder(context.applicationContext)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(4L * 1024 * 1024).build() }
        .diskCache(null)
        .build().also { loader = it }
}

internal fun defaultBookCoverRequest(context: Context, text: DefaultBookCoverRenderer.Text,
    pixelWidth: Int, pixelHeight: Int, cacheArtwork: Boolean = true): ImageRequest {
    val width = pixelWidth.coerceIn(1, DefaultBookCoverRenderer.DEFAULT_WIDTH)
    val height = pixelHeight.coerceIn(1, DefaultBookCoverRenderer.DEFAULT_HEIGHT)
    val normalized = text.copy(title = DefaultBookCoverRenderer.displayTitle(context, text.title).take(512),
        author = text.author.trim().take(256))
    // Length framing prevents collisions between IDs/titles/authors. This is a local artwork key,
    // never the URL of a failed remote cover. Sizes are part of the key, not just the request.
    val key = "default-cover:" + listOf(DefaultBookCoverRenderer.STYLE_VERSION, normalized.bookId, normalized.title, normalized.author,
        width.toString(), height.toString()).joinToString("") { "${it.length}:$it" }
    return ImageRequest.Builder(context).data(normalized).size(width, height).memoryCacheKey(key)
        // Loading/error artwork may read an existing entry, but must not retain a second image
        // for every remotely covered book. Visible painters still own their in-flight bitmaps.
        .memoryCachePolicy(if (cacheArtwork) CachePolicy.ENABLED else CachePolicy.READ_ONLY)
        .fetcherCoroutineContext(Dispatchers.Default)
        .fetcherFactory<DefaultBookCoverRenderer.Text> { data, _, _ ->
            Fetcher {
                trace("DefaultBookCover.raster") {
                    // Coil MEMORY means an in-memory Bitmap source; only cache hits use MEMORY_CACHE.
                    ImageFetchResult(DefaultBookCoverRenderer.render(context, data.title, width, height,
                        data.bookId, data.author).asImage(), isSampled = false, dataSource = DataSource.MEMORY)
                }
            }
        }.build()
}
