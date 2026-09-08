package io.nightfish.lightnovelreader.api.text

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ComponentDataJsonElementSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.reflect.KClass

/**
 * 章节内容组件处理器
 * 用于对章节内容JSON中的特定类型组件批量应用变换操作
 *
 * @param serializerMap 组件id到其JSON序列化器的映射
 * @param dataKClassMap 组件id到其数据类KClass的映射
 * @param content 当前章节内容的JSON对象
 *
 * @since Api 2
 */
class ComponentProcessor(
    val serializerMap: Map<String, ComponentDataJsonElementSerializer<out AbstractContentComponentData>>,
    val dataKClassMap: Map<String, KClass<out AbstractContentComponentData>>,
    var content: JsonObject
) {
    /**
     * 对指定类型的所有组件数据应用变换
     * 只替换匹配组件的 data；根对象、组件扩展字段和不匹配/不完整的数组项原样保留
     *
     * @param T 需要处理的组件数据类型
     * @param block 接收原组件数据并返回变换后数据的函数
     *
     * @since Api 2
     */
    inline fun <reified T: AbstractContentComponentData> process(crossinline block: (T) -> T) {
        val components = content["components"] as? JsonArray ?: return
        content = buildJsonObject {
            content.forEach { (key, value) -> put(key, value) }
            put("components", buildJsonArray {
                components.forEach { element ->
                    val component = element as? JsonObject
                    if (component == null) {
                        add(element)
                        return@forEach
                    }

                    val id = (component["id"] as? JsonPrimitive)?.content
                    if (id == null) {
                        add(component)
                        return@forEach
                    }
                    val data = component["data"] as? JsonObject
                    val serializer = serializerMap[id]
                    if (data == null || dataKClassMap[id] != T::class || serializer == null) {
                        add(component)
                        return@forEach
                    }

                    add(buildJsonObject {
                        component.forEach { (key, value) -> put(key, value) }
                        put("data", block(serializer.fromJsonElement(data) as T).toJsonElement())
                    })
                }
            })
        }
    }

    /**
     * 获取处理后的章节内容JSON对象
     *
     * @return 处理后的[JsonObject]
     *
     * @since Api 2
     */
    fun get() = content
}
