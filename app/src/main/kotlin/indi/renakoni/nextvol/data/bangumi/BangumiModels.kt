package indi.renakoni.nextvol.data.bangumi

import io.nightfish.lightnovelreader.api.book.BookInformation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val bangumiJson = Json { ignoreUnknownKeys = true }

@Serializable
data class BangumiSubject(
    val id: Int,
    val type: Int = 1,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val platform: String = "",
    val series: Boolean = false,
    val volumes: Int = 0,
    val infobox: JsonArray = JsonArray(emptyList()),
) {
    val title: String get() = nameCn.ifBlank { name }
    fun values(key: String): List<String> = infobox.flatMap { item ->
        val entry = item as? JsonObject ?: return@flatMap emptyList()
        if (entry["key"]?.jsonPrimitive?.contentOrNull != key) return@flatMap emptyList()
        when (val value = entry["value"]) {
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            is JsonArray -> value.mapNotNull { (it as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull }
            else -> emptyList()
        }
    }
    val isNovel: Boolean get() = type == 1 && platform in setOf("小说", "小説", "轻小说", "輕小說")
}

@Serializable
data class BangumiRelatedSubject(
    val id: Int,
    val type: Int,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val relation: String = "",
)

@Serializable
data class BangumiPerson(val id: Int, val name: String, val relation: String)

@Serializable
data class BangumiAvatar(val large: String = "", val medium: String = "", val small: String = "") {
    val url: String get() = medium.ifBlank { large.ifBlank { small } }
}

@Serializable
data class BangumiUser(val id: Int, val username: String, val nickname: String = "", val avatar: BangumiAvatar = BangumiAvatar())

@Serializable
data class BangumiCollection(
    val type: Int,
    @SerialName("vol_status") val volumes: Int = 0,
    @SerialName("ep_status") val chapters: Int = 0,
    val private: Boolean = false,
)

@Serializable
data class BangumiSearchPage(val data: List<BangumiSubject> = emptyList(), val total: Int = 0)

data class BangumiCandidate(
    val subject: BangumiSubject,
    val titleMatches: Boolean,
    val authorMatches: Boolean,
    val authorConflicts: Boolean,
    val publisherMatches: Boolean,
)

@Serializable
data class BangumiVolumeMapping(
    val volumeId: String,
    val title: String,
    /** Same key on multiple source volumes means one split publication, counted once. */
    val editionKey: String? = null,
    val chapterIds: Set<String> = emptySet(),
    val complete: Boolean = false,
)

@Serializable
enum class BangumiSyncStatus {
    READY, PENDING, SYNCED, REMOTE_AHEAD, AUTH_REQUIRED, REMOTE_CHANGED, MAPPING_CHANGED,
    REMOTE_STATE, REMOTE_MISSING, REQUEST_REJECTED, OFFLINE, MATCH_REQUIRED;

    val successful: Boolean get() = this in setOf(READY, SYNCED, REMOTE_AHEAD)
}

@Serializable
data class BangumiBinding(
    val bookTitle: String,
    val subjectTitle: String,
    val revision: String,
    val mapping: List<BangumiVolumeMapping>,
    val baseline: Set<String> = emptySet(),
    val acknowledged: Set<String> = emptySet(),
    val pendingEditions: Set<String> = emptySet(),
    val target: Int = 0,
    val remote: Int = 0,
    val lastSyncedAt: Long? = null,
    val status: BangumiSyncStatus = BangumiSyncStatus.PENDING,
    val initializeCollection: Boolean = true,
    val forceSync: Boolean = false,
    val privateCollection: Boolean = true,
    val retryAt: Long = 0,
    val automatic: Boolean = false,
)

data class BangumiBookPreview(
    val book: BookInformation,
    val subject: BangumiSubject,
    val mapping: List<BangumiVolumeMapping>,
    val remote: BangumiCollection?,
    val accountId: Int,
    val accountGeneration: String,
)
