package indi.dmzz_yyhyy.lightnovelreader.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier

/** Only the monotonic account generation is needed to prevent reopening an old account's broker storage. */
interface SourceSessionEpochStore {
    fun read(source: Identifier): Long
    fun write(source: Identifier, generation: Long)

    class Memory : SourceSessionEpochStore {
        private val values = mutableMapOf<Identifier, Long>()
        override fun read(source: Identifier) = values[source] ?: 0
        override fun write(source: Identifier, generation: Long) { values[source] = generation }
    }
}
