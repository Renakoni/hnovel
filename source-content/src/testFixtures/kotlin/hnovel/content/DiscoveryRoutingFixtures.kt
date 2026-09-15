package hnovel.content

import kotlinx.serialization.json.*

/** Reviewed catalogue-only samples, with separate provenance for synthetic and dynamic cases. */
object DiscoveryRoutingFixtures {
    val samples: List<JsonObject> by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/discovery-routing-samples.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("samples").jsonArray.map { it.jsonObject }
    }
}
