package hnovel.content

import hnovel.execution.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Android supplies the isolated service; JVM fixtures execute the same worker and broker protocol. */
fun interface RuleTaskRunner {
    suspend fun execute(identity: ExecutionIdentity, task: ExecutionTask, limits: ExecutionLimits,
        broker: SourceExecutionBroker): ExecutionResult
}

enum class ContentError { Unavailable, LoginRequired, MissingCapability, BrowserRequired, PermissionDenied,
    Network, InvalidRule, EmptyContent, RepeatedPage, Limit, Storage, AddressDenied, Dns, UnsupportedDependency }

/** Same interpretation for direct requests and host-denied script requests. No URL crosses this boundary. */
internal fun hnovel.network.FailureCode.contentError(): ContentError = when (this) {
    hnovel.network.FailureCode.OriginDenied -> ContentError.PermissionDenied
    hnovel.network.FailureCode.AddressDenied -> ContentError.AddressDenied
    hnovel.network.FailureCode.Dns -> ContentError.Dns
    hnovel.network.FailureCode.ResponseTooLarge, hnovel.network.FailureCode.Timeout -> ContentError.Limit
    hnovel.network.FailureCode.BrowserRequired -> ContentError.BrowserRequired
    else -> ContentError.Network
}

/** Only stable codes and definition field names cross into UI/logging. */
class SourceContentException(val code: ContentError, val field: String,
    val denial: hnovel.network.OriginDenial? = null, val dependency: hnovel.rules.ScriptDependency? = null) : Exception("${code.name}: $field")

@Serializable data class ScriptState(
    val metadata: JsonObject = JsonObject(emptyMap()),
    val variables: Map<String, String> = emptyMap(),
    val bigVariables: Map<String, String> = emptyMap(),
    val inherited: Map<String, String> = emptyMap(),
)

@Serializable data class RuleBook(val id: String, val title: String = "", val author: String = "",
    val description: String = "", val coverUrl: String = "", val tocUrl: String = id,
    val tags: List<String> = emptyList(), val wordCount: String = "", val latestChapter: String = "",
    val updateTime: String = "", val state: ScriptState = ScriptState(), val observedUpdate: Long = 0)

@Serializable data class RuleChapter(val id: String, val title: String, val isVolume: Boolean = false,
    val isVip: Boolean = false, val isPay: Boolean = false, val updateTime: String = "",
    val state: ScriptState = ScriptState())

@Serializable data class ContentPart(val text: String? = null, val image: String? = null)
data class RuleContent(val id: String, val title: String, val parts: List<ContentPart>,
    val previous: String?, val next: String?)

@Serializable internal data class PageDocument(val body: String, val url: String)
@Serializable internal data class BookRecord(val revision: String, val book: RuleBook,
    val informationLoaded: Boolean = false, val document: PageDocument? = null,
    val chapters: List<RuleChapter> = emptyList())
