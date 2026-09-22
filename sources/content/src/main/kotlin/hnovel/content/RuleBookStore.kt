package hnovel.content

import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import hnovel.network.*
import kotlinx.serialization.json.Json

/** Source storage retains rule state and catalog snapshots across account changes. */
internal class RuleBookStore(private val session: SourceSession, private val authority: ExecutionAuthority,
    private val identity: ExecutionIdentity) {
    fun read(bookId: String): BookRecord? = authority.authorized(identity) {
        val current = session.read(StorageRequest(StorageArea.BookState, key(bookId)))
        // Existing installations stored these snapshots beside small script settings.
        val result = if (current is StorageResult.Value && current.value == null)
            session.read(StorageRequest(StorageArea.Config, key(bookId))) else current
        if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "bookState")
        result.value?.let { Json.decodeFromString(BookRecord.serializer(), it) }?.takeIf { it.book.id == bookId }
    }
    fun write(record: BookRecord) = write(listOf(record))
    fun write(records: List<BookRecord>) = authority.authorized(identity) {
        if (records.isEmpty()) return@authorized
        val values = records.associate { key(it.book.id) to Json.encodeToString(BookRecord.serializer(), it) }
        val result = session.writeBookStates(values)
        if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "bookState")
        // Release the old quota only after the new snapshot has been committed atomically.
        if (values.keys.any { session.write(StorageRequest(StorageArea.Config, it)) !is StorageResult.Value })
            throw SourceContentException(ContentError.Storage, "bookState")
        Unit
    }
    private fun key(bookId: String) = "content/book/" + digest(bookId)
}
