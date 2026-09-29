package indi.renakoni.nextvol.data.web

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A persisted task may never continue its request chain using another account or revision. */
internal class SourceRequestVersion(private val expected: SourceMetadata) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceRequestVersion>

    fun check(actual: SourceMetadata?) {
        if (actual?.id != expected.id || actual.revision != expected.revision ||
            actual.accountGeneration != expected.accountGeneration) throw SourceUnavailableException(expected.id)
    }
}
