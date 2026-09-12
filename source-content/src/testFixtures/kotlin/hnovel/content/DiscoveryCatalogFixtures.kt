package hnovel.content

import kotlinx.serialization.json.*

/** Unmodified catalogue metadata from pinned XIU2/Yuedu; no source headers or credentials. */
object DiscoveryCatalogFixtures {
    private val catalogs by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/discovery-catalogs.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("catalogs").jsonArray
    }

    fun rows(sourceIndex: Int): JsonArray = catalogs.single {
        it.jsonObject.getValue("index").jsonPrimitive.int == sourceIndex
    }.jsonObject.getValue("rows").jsonArray
}
