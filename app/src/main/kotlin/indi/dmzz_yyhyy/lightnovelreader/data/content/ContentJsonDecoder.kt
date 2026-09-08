package indi.dmzz_yyhyy.lightnovelreader.data.content

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.InvocationTargetException
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
                    val componentClass = registry.componentClass(id)
                        ?: return@map error("component class not found\nid=$id")
                    val dataClass = registry.dataClass(id)
                        ?: return@map error("component data class not found\nid=$id")
                    val serializer = registry.serializer(id)
                        ?: return@map error("component data serializer not found\nid=$id")
                    create(componentClass, dataClass) { serializer.fromJsonElement(data) }
                        ?: error("failed to init component")
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (wrapped: InvocationTargetException) {
                    when (val cause = wrapped.targetException) {
                        is CancellationException -> throw cause
                        is Error -> throw cause
                        else -> error("failed to create component")
                    }
                } catch (_: Exception) {
                    error("failed to create component")
                }
            }
    }

    // Export skips structurally invalid entries, uses exact IDs and propagates serializer errors.
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
}
