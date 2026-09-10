package indi.dmzz_yyhyy.lightnovelreader.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.michaelbull.result.getOrElse
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceRuntime
import okio.Buffer
import java.io.IOException

/** A missing runtime is an explicit cache-only request, never an HTTP URL fallback. */
internal data class BoundSourceImage(val image: SourceImage, val key: String, val runtime: SourceRuntime?,
    val accountCache: SourceImageAccountCache? = null, val commit: (() -> Unit) -> Unit = { it() })

internal class SourceImageFetcher(private val request: BoundSourceImage, private val options: Options,
    private val cache: DiskCache?) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        if (options.diskCachePolicy.readEnabled) cache?.openSnapshot(request.key)?.let { snapshot ->
            return SourceFetchResult(ImageSource(snapshot.data, cache.fileSystem, request.key, snapshot), null, DataSource.DISK)
        }
        val runtime = request.runtime ?: throw IOException("Source image is not cached")
        val result = runtime.imageBytes(request.image.book.remoteId, request.image.uri, request.image.cover)
        val bytes = result.getOrElse { throw it.throwable ?: IOException(it.message) }
        if (options.diskCachePolicy.writeEnabled && cache != null) request.commit {
            cache.openEditor(request.key)?.let { editor ->
                try {
                    cache.fileSystem.write(editor.metadata) { }
                    cache.fileSystem.write(editor.data) { write(bytes) }
                    editor.commit()
                } catch (failure: IOException) { editor.abort() }
            }
        }
        return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), null, DataSource.NETWORK)
    }

    class Factory : Fetcher.Factory<BoundSourceImage> {
        override fun create(data: BoundSourceImage, options: Options, imageLoader: ImageLoader): Fetcher {
            data.accountCache?.attach(imageLoader)
            return SourceImageFetcher(data, options, imageLoader.diskCache)
        }
    }
}
