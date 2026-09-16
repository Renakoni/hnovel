package indi.renakoni.nextvol.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.michaelbull.result.getOrElse
import indi.renakoni.nextvol.data.web.SourceRuntime
import okio.Buffer
import java.io.IOException

/** A missing runtime is an explicit cache-only request, never an HTTP URL fallback. */
internal data class BoundSourceImage(val image: SourceImage, val key: String, val runtime: SourceRuntime?)

internal class SourceImageFetcher(private val request: BoundSourceImage, private val options: Options,
    private val cache: DiskCache?) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        if (options.diskCachePolicy.readEnabled) cache?.openSnapshot(request.key)?.let { snapshot ->
            return SourceFetchResult(ImageSource(snapshot.data, cache.fileSystem, request.key, snapshot), null, DataSource.DISK)
        }
        val runtime = request.runtime ?: throw IOException("Source image is not cached")
        val result = runtime.imageBytes(request.image.book.remoteId, request.image.uri, request.image.cover)
        val bytes = result.getOrElse { throw it.throwable ?: IOException(it.message) }
        // Coil decodes after fetch(). Do not persist obvious HTML/error bodies, since a
        // successful HTTP status is common for block pages and would otherwise poison the
        // source image cache until its revision changes.
        if (options.diskCachePolicy.writeEnabled && cache != null && looksLikeImage(bytes)) {
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

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) return true
        if (bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII).startsWith("GIF8")) return true
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") return true
        if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return true
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, 256)).toString(Charsets.UTF_8).trimStart()
        return prefix.startsWith("<svg", true) || prefix.startsWith("<?xml", true) && prefix.contains("<svg", true)
    }

    class Factory : Fetcher.Factory<BoundSourceImage> {
        override fun create(data: BoundSourceImage, options: Options, imageLoader: ImageLoader): Fetcher =
            SourceImageFetcher(data, options, imageLoader.diskCache)
    }
}
