package indi.dmzz_yyhyy.lightnovelreader.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The actual registration making a call, including adapters sharing one native API object. */
internal class SourceRequestOwner(val id: Identifier) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceRequestOwner>
}
