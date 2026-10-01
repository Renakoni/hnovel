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
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import kotlinx.coroutines.Dispatchers

@Composable
fun DefaultBookCover(title: String, width: Dp, height: Dp, bookId: String = "", author: String = "") {
    val context = LocalContext.current
    val displayTitle = title.trim().ifBlank { stringResource(R.string.cover_untitled) }
    val description = stringResource(R.string.cover_description, displayTitle)
    val text = remember(bookId, displayTitle, author) { DefaultBookCoverRenderer.Text(bookId, displayTitle, author) }
    val pixelWidth = with(LocalDensity.current) { width.roundToPx() }
    val pixelHeight = with(LocalDensity.current) { height.roundToPx() }
    val request = remember(context, text, pixelWidth, pixelHeight) {
        defaultBookCoverRequest(context, text, pixelWidth, pixelHeight)
    }
    val placeholder = remember(context, text) { ColorPainter(Color(DefaultBookCoverRenderer.backgroundColor(context, text))) }
    // Static artwork becomes one texture; alpha transitions no longer compile programs for each
    // text/stroke primitive. Coil owns the bounded memory cache and cancels abandoned requests.
    AsyncImage(model = request, contentDescription = description, modifier = Modifier.size(width, height),
        placeholder = placeholder, contentScale = ContentScale.FillBounds)
}

internal fun defaultBookCoverRequest(context: Context, text: DefaultBookCoverRenderer.Text,
    pixelWidth: Int, pixelHeight: Int): ImageRequest {
    val width = pixelWidth.coerceIn(1, DefaultBookCoverRenderer.DEFAULT_WIDTH)
    val height = pixelHeight.coerceIn(1, DefaultBookCoverRenderer.DEFAULT_HEIGHT)
    val normalized = text.copy(title = DefaultBookCoverRenderer.displayTitle(context, text.title).take(512),
        author = text.author.trim().take(256))
    // Length framing prevents collisions between IDs/titles/authors. This is a local artwork key,
    // never the URL of a failed remote cover. Sizes are part of the key, not just the request.
    val key = "default-cover:" + listOf(normalized.bookId, normalized.title, normalized.author,
        width.toString(), height.toString()).joinToString("") { "${it.length}:$it" }
    return ImageRequest.Builder(context).data(normalized).size(width, height).memoryCacheKey(key)
        .fetcherCoroutineContext(Dispatchers.Default)
        .fetcherFactory<DefaultBookCoverRenderer.Text> { data, _, _ ->
            Fetcher {
                trace("DefaultBookCover.raster") {
                    ImageFetchResult(DefaultBookCoverRenderer.render(context, data.title, width, height,
                        data.bookId, data.author).asImage(), isSampled = false, dataSource = DataSource.MEMORY)
                }
            }
        }.build()
}
