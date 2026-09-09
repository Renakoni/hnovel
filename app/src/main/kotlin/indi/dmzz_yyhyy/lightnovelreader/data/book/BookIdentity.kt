package indi.dmzz_yyhyy.lightnovelreader.data.book

import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.Base64

/** Stable identity, independent of source name, revision, registration and account. */
@Serializable
data class SourceBookId(
    @Serializable(with = BookSourceIdSerializer::class) val sourceId: Identifier,
    val remoteId: String,
) {
    init { require(remoteId.isNotEmpty()) { "Remote book ID must not be empty" } }
    val storageKey: String get() = BookIdentity.encode("b", listOf(sourceId.namespace, sourceId.id, remoteId))
    /** Bounded directory component even when a remote ID is an entire URL. */
    val fileKey: String get() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(storageKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        fun fromStorageKey(key: String): SourceBookId {
            val fields = BookIdentity.decode(key, "b", 3)
            return SourceBookId(Identifier(fields[0], fields[1]), fields[2])
        }
    }
}

@Serializable
data class SourceChapterId(val book: SourceBookId, val remoteId: String) {
    init { require(remoteId.isNotEmpty()) { "Remote chapter ID must not be empty" } }
    val storageKey: String get() = BookIdentity.encode("c", listOf(book.sourceId.namespace, book.sourceId.id, book.remoteId, remoteId))

    companion object {
        fun fromStorageKey(key: String): SourceChapterId {
            val fields = BookIdentity.decode(key, "c", 4)
            return SourceChapterId(SourceBookId(Identifier(fields[0], fields[1]), fields[2]), fields[3])
        }
    }
}

/** Versioned, reversible internal keys. URL-safe Base64 also survives list and route transport. */
object BookIdentity {
    private val strings = ListSerializer(String.serializer())
    private val wenku8 = Identifier("lightnovelreader", "Wenku8")

    internal fun encode(kind: String, fields: List<String>): String =
        "lnr1.$kind." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Json.encodeToString(strings, fields).toByteArray(Charsets.UTF_8))

    internal fun decode(key: String, kind: String, count: Int): List<String> {
        val prefix = "lnr1.$kind."
        require(key.startsWith(prefix)) { "Expected a source-qualified $kind key" }
        val fields = Json.decodeFromString(strings, Base64.getUrlDecoder()
            .decode(key.removePrefix(prefix)).toString(Charsets.UTF_8))
        require(fields.size == count && encode(kind, fields) == key) { "Invalid identity encoding" }
        return fields
    }

    /** Temporary legacy host ingress: bare IDs mean Wenku8, never the browsing selection. */
    fun book(id: String): SourceBookId = if (id.startsWith("lnr1.")) SourceBookId.fromStorageKey(id)
        else SourceBookId(wenku8, id)

    fun bookKey(id: String): String = book(id).storageKey

    fun chapter(id: String, book: SourceBookId): SourceChapterId =
        if (id.startsWith("lnr1.")) SourceChapterId.fromStorageKey(id).also {
            require(it.book == book) { "Chapter belongs to another book" }
        } else SourceChapterId(book, id)

    fun volumeKey(book: SourceBookId, remoteId: String): String =
        encode("v", listOf(book.sourceId.namespace, book.sourceId.id, book.remoteId, remoteId))

    fun volumeRemoteId(key: String, book: SourceBookId): String {
        val fields = decode(key, "v", 4)
        require(fields.take(3) == listOf(book.sourceId.namespace, book.sourceId.id, book.remoteId))
        return fields[3]
    }
}

/** Identifier's legacy colon serializer is unsuitable for arbitrary imported identity fields. */
object BookSourceIdSerializer : KSerializer<Identifier> {
    private val strings = ListSerializer(String.serializer())
    override val descriptor = strings.descriptor
    override fun serialize(encoder: Encoder, value: Identifier) =
        encoder.encodeSerializableValue(strings, listOf(value.namespace, value.id))
    override fun deserialize(decoder: Decoder): Identifier {
        val fields = decoder.decodeSerializableValue(strings)
        require(fields.size == 2)
        return Identifier(fields[0], fields[1])
    }
}
