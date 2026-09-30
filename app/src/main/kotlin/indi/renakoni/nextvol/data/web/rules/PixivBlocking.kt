package indi.renakoni.nextvol.data.web.rules

import hnovel.network.SourceSession
import hnovel.network.StorageArea
import hnovel.network.StorageRequest
import hnovel.network.StorageResult
import hnovel.content.RuleBook
import hnovel.content.RuleListPage
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import indi.renakoni.nextvol.data.book.SourceBookId
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

enum class PixivBlockKind { Book, Author, Tag, Caption }

@Serializable
data class PixivBlockRule(val kind: PixivBlockKind, val value: String, val label: String)

@Serializable
data class PixivBlockBook(val key: String, val title: String, val authorId: String = "",
    val author: String = "", val tags: List<String> = emptyList())

private fun RuleBook.blockingTags(data: PixivBlockBook?): List<String> =
    (tags.map { it.removePrefix("#") } + data?.tags.orEmpty()).filter(String::isNotBlank).distinct()

@Serializable
internal data class PixivLocalPreferences(val blocks: List<PixivBlockRule> = emptyList(),
    val likedTags: List<String> = emptyList(), val bookmarkUsers: Map<String, String> = emptyMap())

/** Shared with the bundled script. Source config survives account rotation; cache does not. */
internal object PixivPreferenceStore {
    private const val KEY = "value:nextvol.pixiv.preferences"

    fun read(session: SourceSession): PixivLocalPreferences {
        fun read(area: StorageArea, key: String): String? {
            val result = session.read(StorageRequest(area, key))
            check(result is StorageResult.Value) { "Pixiv preferences are unavailable" }
            return result.value
        }
        read(StorageArea.Config, KEY)?.let { return Json.decodeFromString(it) }
        fun cached(key: String) = read(StorageArea.Cache, "value:$key")?.let(Json::parseToJsonElement)
        fun words(key: String, fallback: String? = null): List<String> =
            ((cached(key)?.takeUnless { it == JsonNull } ?: fallback?.let(::cached)) as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.distinct()
        fun authors(key: String) = when (val value = cached(key)) {
            is JsonObject -> value.entries
            is JsonArray -> value.filterIsInstance<JsonObject>().flatMap { it.entries }
            else -> emptyList()
        }.mapNotNull { (id, name) ->
            (name as? JsonPrimitive)?.contentOrNull?.let { id to it }
        }.toMap()
        val blockedAuthors = words("blockAuthorList").associateWith { it } + authors("blockAuthorMap")
        val preferences = PixivLocalPreferences(
            blocks = blockedAuthors.map { (id, name) -> PixivBlockRule(PixivBlockKind.Author, id, name) } +
                words("blockTags", "tagsBlockWords").map { PixivBlockRule(PixivBlockKind.Tag, it, it) } +
                words("blockCaption", "captionBlockWords").map { PixivBlockRule(PixivBlockKind.Caption, it, it) },
            likedTags = words("likeTags"),
            bookmarkUsers = if (cached("likeAuthorsMap") == null) authors("likeAuthors") else authors("likeAuthorsMap"),
        )
        write(session, preferences)
        return preferences
    }

    fun write(session: SourceSession, value: PixivLocalPreferences) {
        check(session.write(StorageRequest(StorageArea.Config, KEY, Json.encodeToString(value))) is StorageResult.Value)
    }
}

/** A single config read per result batch; never runs rules or requests book details. */
internal class PixivBookFilter(private val session: SourceSession) {
    suspend fun filter(page: RuleListPage) = withContext(Dispatchers.IO) { filter(page.freshBooks) }

    private fun filter(books: List<RuleBook>): List<RuleBook> = try {
        val rules = PixivPreferenceStore.read(session).blocks
        val values = rules.groupBy { it.kind }.mapValues { (_, rows) -> rows.mapTo(hashSetOf()) { it.value } }
        if (rules.isEmpty()) books else books.filter { book ->
            val data = book.state.variables["nextvolPixivBook"]?.let { Json.decodeFromString<PixivBlockBook>(it) }
            data?.key !in values[PixivBlockKind.Book].orEmpty() &&
                data?.authorId !in values[PixivBlockKind.Author].orEmpty() &&
                book.blockingTags(data).none { it in values[PixivBlockKind.Tag].orEmpty() } &&
                values[PixivBlockKind.Caption].orEmpty().none { book.description.contains(it) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw SourceContentException(ContentError.InvalidRule, "pixiv.blocking").apply { initCause(failure) }
    }
}

/** Only the reviewed bundled Pixiv adaptation participates, not arbitrary imported sources. */
@Singleton
class PixivBlocking @Inject constructor(private val sources: ImportedRuleSources) {
    suspend fun book(book: SourceBookId): PixivBlockBook? {
        if (!sources.supportsPixivBlocking(book.sourceId)) return null
        val target = sources.loginTarget(book.sourceId)
        val canonical = target.rules.canonicalBookId(book.remoteId)
        val saved = target.rules.cachedInformation(canonical) ?: return null
        return saved.state.variables["nextvolPixivBook"]?.let {
            val data = Json.decodeFromString<PixivBlockBook>(it)
            data.copy(tags = saved.blockingTags(data))
        }
    }

    suspend fun rules(id: Identifier): List<PixivBlockRule> = sources.withPixivPreferences(id) {
        PixivPreferenceStore.read(it).blocks
    }

    suspend fun add(id: Identifier, rules: List<PixivBlockRule>) {
        require(rules.isNotEmpty() && rules.all { it.value.isNotBlank() && it.label.isNotBlank() })
        sources.withPixivPreferences(id) { session ->
            val before = PixivPreferenceStore.read(session)
            val next = (before.blocks + rules).distinctBy { it.kind to it.value }
            if (next != before.blocks) PixivPreferenceStore.write(session, before.copy(blocks = next))
        }
        sources.refreshDiscovery(id)
    }

    suspend fun remove(id: Identifier, rule: PixivBlockRule) {
        sources.withPixivPreferences(id) { session ->
            val before = PixivPreferenceStore.read(session)
            PixivPreferenceStore.write(session, before.copy(blocks = before.blocks.filterNot {
                it.kind == rule.kind && it.value == rule.value
            }))
        }
        sources.refreshDiscovery(id)
    }
}
