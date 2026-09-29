package indi.renakoni.nextvol.data.web

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Snapshot resumable tasks on user action; commit only after the challenged request succeeds. */
class BackgroundSourceRequest(
    val prepareResume: suspend () -> (suspend () -> Unit)?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BackgroundSourceRequest>
}
