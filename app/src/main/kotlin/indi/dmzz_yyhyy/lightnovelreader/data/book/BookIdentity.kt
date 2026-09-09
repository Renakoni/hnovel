package indi.dmzz_yyhyy.lightnovelreader.data.book

import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.Serializable

/** Stable host identity for a book. The remote id is meaningful only inside sourceId. */
@Serializable
data class SourceBookId(
    val sourceId: Identifier,
    val remoteId: String,
) {
    init { require(remoteId.isNotEmpty()) { "remote book id must not be empty" } }

    val storageKey: String get() = StorageKey.encode(sourceId, remoteId)
}

/** A chapter is owned by its source-qualified book, not by a globally unique chapter id. */
@Serializable
data class SourceChapterId(
    val book: SourceBookId,
    val remoteId: String,
) {
    init { require(remoteId.isNotEmpty()) { "remote chapter id must not be empty" } }

    val storageKey: String get() = StorageKey.encode(book.sourceId, StorageKey.encodePair(book.remoteId, remoteId))
}

/** Length-prefix encoding avoids collisions from delimiters in user/source supplied ids. */
internal object StorageKey {
    fun encodePair(first: String, second: String): String = buildString {
        append(first.length).append(':').append(first)
        append(second.length).append(':').append(second)
    }

    fun decodePair(value: String): Pair<String, String>? {
        var offset = 0
        fun read(): String? {
            val separator = value.indexOf(':', offset)
            if (separator < offset) return null
            val length = value.substring(offset, separator).toIntOrNull() ?: return null
            val start = separator + 1
            val end = start + length
            if (length < 0 || end > value.length) return null
            offset = end
            return value.substring(start, end)
        }
        val first = read() ?: return null
        val second = read() ?: return null
        return (first to second).takeIf { offset == value.length }
    }

    fun encode(source: Identifier, value: String): String = buildString {
        append(source.namespace.length).append(':').append(source.namespace)
        append(source.id.length).append(':').append(source.id)
        append(value.length).append(':').append(value)
    }

    fun decode(source: Identifier, key: String): String? {
        var offset = 0
        fun read(): String? {
            val separator = key.indexOf(':', offset)
            if (separator <= offset) return null
            val length = key.substring(offset, separator).toIntOrNull() ?: return null
            val start = separator + 1
            val end = start + length
            if (end > key.length) return null
            offset = end
            return key.substring(start, end)
        }
        if (read() != source.namespace || read() != source.id) return null
        return read()?.takeIf { offset == key.length }
    }
}
