package indi.dmzz_yyhyy.lightnovelreader.data.content

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/** JSON traversal and decoding policy. Does not access a plugin injector or create error UI. */
@Singleton
class ContentJsonDecoder @Inject constructor(
    private val registry: ContentComponentRegistry,
) {
    // Construction stays interleaved with decoding, one component at a time. The factory invokes
    // decodeData after capturing its injection map, preserving the existing evaluation order.
    internal fun <T> decodeComponents(
        content: JsonObject,
        create: (
            componentClass: KClass<out AbstractContentComponent<out AbstractContentComponentData>>,
            dataClass: KClass<out AbstractContentComponentData>,
            decodeData: () -> AbstractContentComponentData,
        ) -> T?,
        error: (String) -> T,
    ): List<T> = content["components"]
        ?.jsonArray
        ?.mapNotNull { it.jsonObject }
        ?.map { component ->
            val id = component["id"]?.jsonPrimitive?.content
                ?.let { if (it.contains(":")) it else "lightnovelreader:$it" }
                ?: return@map error("component id not found")
            val data = component["data"]?.jsonObject
                ?: return@map error("component data not found\nid=$id")
            val componentClass = registry.componentClass(id)
                ?: return@map error("component class not found\nid=$id")
            val dataClass = registry.dataClass(id)
                ?: return@map error("component data class not found\nid=$id")
            val serializer = registry.serializer(id)
                ?: return@map error("component data serializer not found\nid=$id")
            create(componentClass, dataClass) { serializer.fromJsonElement(data) }
                ?: error("failed to init component")
        } ?: listOf(error("error to load components from json"))

    // Export retains its existing policy: exact IDs, skip missing entries, propagate decoder errors.
    fun getDataFromJsonObject(content: JsonObject, block: (AbstractContentComponentData) -> Unit) {
        content["components"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.forEach {
                val id = it["id"]?.jsonPrimitive?.content
                    ?: return@forEach
                val data = it["data"]?.jsonObject
                    ?: return@forEach
                val serializer = registry.serializeMap[id]
                    ?: return@forEach
                block(serializer.fromJsonElement(data))
            }
    }
}
