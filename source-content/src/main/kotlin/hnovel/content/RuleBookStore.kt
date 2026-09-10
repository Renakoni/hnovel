package hnovel.content

import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import hnovel.network.*
import kotlinx.serialization.json.Json

/** Source storage retains rule state and catalog snapshots across account changes. */
internal class RuleBookStore(private val session: SourceSession, private val authority: ExecutionAuthority,
    private val identity: ExecutionIdentity) {
    fun read(bookId: String): BookRecord? = authority.authorized(identity) {
        val result = session.read(StorageRequest(StorageArea.Config, key(bookId)))
        if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "bookState")
        result.value?.let { Json.decodeFromString(BookRecord.serializer(), it) }?.takeIf { it.book.id == bookId }
    }
    fun write(record: BookRecord) = authority.authorized(identity) {
        val result = session.write(StorageRequest(StorageArea.Config, key(record.book.id), Json.encodeToString(BookRecord.serializer(), record)))
        if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "bookState")
        Unit
    }
    private fun key(bookId: String) = "content/book/" + digest(bookId)
}
