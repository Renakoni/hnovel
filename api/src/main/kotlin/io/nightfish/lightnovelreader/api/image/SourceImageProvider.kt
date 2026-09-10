package io.nightfish.lightnovelreader.api.image

import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.error.WebRequestError

/** Optional image transport/decoding capability. The host owns identity and its image caches. */
interface SourceImageProvider {
    suspend fun getImage(bookId: String, url: String, cover: Boolean): Result<ByteArray, WebRequestError>
}
