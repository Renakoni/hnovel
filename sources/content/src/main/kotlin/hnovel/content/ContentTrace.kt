package hnovel.content

import kotlinx.serialization.Serializable

/** Only bounded metadata is observable: no URLs, headers, code, variable values or response text. */
@Serializable data class ContentTraceEvent(val kind: String, val field: String, val elapsedMillis: Long,
    val inputSize: Int = 0, val outputSize: Int = 0, val result: String, val ruleCode: String? = null,
    val offset: Int? = null)

fun interface ContentTrace {
    fun record(event: ContentTraceEvent)
    companion object { val None = ContentTrace {} }
}
