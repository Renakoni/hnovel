package indi.dmzz_yyhyy.lightnovelreader.data.content

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ComponentDataJsonElementSerializer
import kotlin.reflect.KClass

/** Registration snapshots used by text processors; exposes no component creation or plugin injection. */
interface ComponentDataRegistry {
    val serializeMap: Map<String, ComponentDataJsonElementSerializer<out AbstractContentComponentData>>
    val dataKClassMap: Map<String, KClass<out AbstractContentComponentData>>
}
