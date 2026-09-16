package indi.renakoni.nextvol.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.SubcomposeAsyncImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import indi.renakoni.nextvol.data.image.ImageTransPostProcessingViewModel
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.image.SourceImage
import io.nightfish.lightnovelreader.api.image.ImagePostProcessingPipeline
import kotlinx.coroutines.Dispatchers
import indi.renakoni.nextvol.R

@Composable
fun Cover(bookId: String, width: Dp, height: Dp, uri: Uri, title: String, rounded: Dp = 8.dp,
    author: String = "", onFallbackChanged: (Boolean) -> Unit = {}) {
    val missing = uri.toString().isBlank()
    LaunchedEffect(bookId, uri) { if (missing) onFallbackChanged(true) }
    Box(
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                shape = RoundedCornerShape(rounded)
                clip = true
            }
    ) {
        if (missing) {
            DefaultBookCover(title, width, height, bookId, author)
        } else {
            RemoteBookCover(bookId, width, height, uri, title, author, onFallbackChanged)
        }
    }
}

@Composable
private fun RemoteBookCover(bookId: String, width: Dp, height: Dp, uri: Uri, title: String, author: String,
    onFallbackChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val imageTransPostProcessingViewModel = hiltViewModel<ImageTransPostProcessingViewModel>()
    val request = remember(uri, bookId) {
        val transformations = imageTransPostProcessingViewModel
            .imageTransPostProcessingManager
            .getCoil3Transformations(ImagePostProcessingPipeline.bookCover, uri)
        ImageRequest.Builder(context)
            .data(SourceImage(BookIdentity.book(bookId), uri.toString(), cover = true))
            .transformations(transformations)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .interceptorCoroutineContext(Dispatchers.Default)
            .build()

    }
    BookCoverImage(request, bookId, width, height, title, author, onFallbackChanged)
}

@Composable
internal fun BookCoverImage(request: ImageRequest, bookId: String, width: Dp, height: Dp, title: String,
    author: String = "", onFallbackChanged: (Boolean) -> Unit = {},
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalContext.current)) {
    SubcomposeAsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = stringResource(R.string.cover_description, title.ifBlank { stringResource(R.string.cover_untitled) }),
        onError = { onFallbackChanged(true) },
        onSuccess = { onFallbackChanged(false) },
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(width, height),
        loading = {
            DefaultBookCover(title, width, height, bookId, author)
        },
        error = {
            // Keep the original request/error and cache key. A retry can still recover its real image.
            DefaultBookCover(title, width, height, bookId, author)
        },
    )
}
