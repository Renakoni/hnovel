package hnovel.content

import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import hnovel.network.*
import kotlinx.serialization.json.Json

/** Source storage retains rule state and catalog snapshots across account changes. */
internal class RuleBookStore(private val session: SourceSession, private val authority: ExecutionAuthority,
    private val identity: ExecutionIdentity) {
    fun canonicalId(bookId: String): String = authority.authorized(identity) {
        var id = bookId
        val visited = mutableSetOf<String>()
        repeat(16) {
            if (!visited.add(id)) throw SourceContentException(ContentError.Storage, "bookAlias")
            val result = session.read(StorageRequest(StorageArea.BookState, aliasKey(id)))
            if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "bookAlias")
            id = result.value ?: return@authorized id
        }
        throw SourceContentException(ContentError.Limit, "bookAlias")
    }
    fun read(bookId: String): BookRecord? = authority.authorized(identity) {
        val id = canonicalId(bookId)
        val current = session.read(StorageRequest(StorageArea.BookState, key(id)))
        // Existing installations stored these snapshots beside small script settings.
        val result = if (current is StorageResult.Value && current.value == null)
            session.read(StorageRequest(StorageArea.Config, key(id))) else current
        if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "bookState")
        result.value?.let { Json.decodeFromString(BookRecord.serializer(), it) }?.takeIf { it.book.id == id }
    }
    fun write(record: BookRecord, requestedId: String = record.book.id) =
        write(listOf(record), if (requestedId == record.book.id) emptyMap() else mapOf(requestedId to record.book.id))
    fun write(records: List<BookRecord>, aliases: Map<String, String> = emptyMap()) = authority.authorized(identity) {
        if (records.isEmpty()) return@authorized
        val values = records.associate { key(it.book.id) to Json.encodeToString(BookRecord.serializer(), it) } +
            aliases.mapKeys { aliasKey(it.key) }
        val result = session.writeBookStates(values)
        if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "bookState")
        // Release the old quota only after the new snapshot has been committed atomically.
        if (values.keys.any { session.write(StorageRequest(StorageArea.Config, it)) !is StorageResult.Value })
            throw SourceContentException(ContentError.Storage, "bookState")
        Unit
    }
    private fun key(bookId: String) = "content/book/" + digest(bookId)
    private fun aliasKey(bookId: String) = "content/alias/" + digest(bookId)
}
