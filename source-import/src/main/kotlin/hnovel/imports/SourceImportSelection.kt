package hnovel.imports

import kotlinx.serialization.json.*

/** Keep one complete definition per identity; a newer upstream revision wins, ties retain file order. */
fun Iterable<SourceCandidate>.deduplicated(): List<SourceCandidate> = groupBy { it.importKey }.values.map { group ->
    if (group.size == 1) group.single() else group.maxBy {
        (Json.parseToJsonElement(it.rawJson).jsonObject["lastUpdateTime"] as? JsonPrimitive)?.longOrNull ?: 0
    }
}
