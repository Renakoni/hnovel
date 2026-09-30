package indi.renakoni.nextvol.data.image

import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** No replay or timer: only currently failed, visible images react to a new source action. */
internal object SourceImageRetryEvents {
    private val events = MutableSharedFlow<Identifier>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests = events.asSharedFlow()
    fun request(source: Identifier) { events.tryEmit(source) }
}
