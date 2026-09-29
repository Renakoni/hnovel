package indi.renakoni.nextvol.data.content

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** JSON traversal and decoding policy, separate from reader component construction. */
@Singleton
class ContentJsonDecoder @Inject constructor(
    private val registry: ContentComponentRegistry,
) {
    // Decode and construct one component at a time, preserving content order.
    internal fun <T> decodeComponents(
        content: JsonObject,
        create: (AbstractContentComponentData) -> T?,
        error: (String) -> T,
    ): List<T> {
        val components = content["components"] ?: return listOf(error("error to load components from json"))
        val array = components as? JsonArray ?: return listOf(error("error to load components from json"))
        return array
            .map { element ->
                val component = element as? JsonObject
                    ?: return@map error("component is not an object")
                try {
                    val id = component["id"]?.jsonPrimitive?.content
                        ?.let { if (it.contains(":")) it else "lightnovelreader:$it" }
                        ?: return@map error("component id not found")
                    val data = component["data"]?.jsonObject
                        ?: return@map error("component data not found\nid=$id")
                    val serializer = registry.serializer(id)
                        ?: return@map error("component class not found\nid=$id")
                    create(serializer.fromJsonElement(data))
                        ?: error("failed to init component")
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    error("failed to create component")
                }
            }
    }

    // Legacy callers skip structurally invalid entries and use exact IDs. Export uses strict decoding below.
    fun getDataFromJsonObject(content: JsonObject, block: (AbstractContentComponentData) -> Unit) {
        (content["components"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.forEach {
                val id = (it["id"] as? JsonPrimitive)?.content
                    ?: return@forEach
                val data = it["data"] as? JsonObject
                    ?: return@forEach
                val serializer = registry.serializeMap[id]
                    ?: return@forEach
                block(serializer.fromJsonElement(data))
            }
    }

    /** Export must account for every component; reader fallback/legacy decoding stays separate. */
    fun decodeForExport(content: JsonObject, block: (AbstractContentComponentData) -> Unit) {
        val components = requireNotNull(content["components"] as? JsonArray) { "Missing content components" }
        components.forEachIndexed { index, element ->
            val component = requireNotNull(element as? JsonObject) { "Invalid component ${index + 1}" }
            val id = requireNotNull((component["id"] as? JsonPrimitive)?.content) { "Missing component type ${index + 1}" }
                .let { if (it.contains(":")) it else "lightnovelreader:$it" }
            val data = requireNotNull(component["data"] as? JsonObject) { "Missing component data ${index + 1}" }
            val serializer = requireNotNull(registry.serializer(id)) { "Unsupported component ${index + 1}" }
            block(serializer.fromJsonElement(data))
        }
    }
}
