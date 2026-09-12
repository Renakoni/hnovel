package hnovel.imports

import hnovel.network.ResourceKind
import hnovel.network.sourceOrigin
import kotlinx.serialization.json.*

/** Inspect already-validated definition data only. A literal reference is a candidate, never a grant. */
data class OriginCandidate(val origin: String, val kind: ResourceKind)

object SourceOriginCandidates {
    private val absolute = Regex("https?://[^\\s\"'<>\\\\/?#]+", RegexOption.IGNORE_CASE)

    fun discover(raw: JsonObject): List<OriginCandidate> {
        val found = linkedSetOf<OriginCandidate>()
        fun scan(value: JsonElement, kind: ResourceKind) {
            if (found.size >= 32) return
            when (value) {
                is JsonObject -> value.forEach { (field, item) ->
                    scan(item, if (field.contains("cover", true) || field.contains("image", true)) ResourceKind.Image else kind)
                }
                is JsonArray -> value.forEach { scan(it, kind) }
                is JsonPrimitive -> if (value.isString) {
                    for (match in absolute.findAll(value.content.replace("\\/", "/"))) {
                        if (found.size >= 32) break
                        val origin = sourceOrigin(match.value) ?: continue
                        // Unresolved template authorities are not actionable website candidates.
                        if (origin.any { it in "{}$," }) continue
                        found += OriginCandidate(origin, kind)
                    }
                }
                else -> Unit
            }
        }
        for (field in listOf("bookSourceUrl", "searchUrl", "exploreUrl", "loginUrl", "jsLib",
            "ruleSearch", "ruleExplore", "ruleBookInfo", "ruleToc", "ruleContent")) {
            val kind = when (field) {
                "jsLib" -> ResourceKind.Script
                "searchUrl" -> ResourceKind.Api
                else -> ResourceKind.Document
            }
            raw[field]?.let { scan(it, kind) }
        }
        return found.toList()
    }
}
