package indi.renakoni.nextvol.data.content

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ComponentDataJsonElementSerializer
import kotlin.reflect.KClass

/** Supported content data types used by text processors, without reader component construction. */
interface ComponentDataRegistry {
    val serializeMap: Map<String, ComponentDataJsonElementSerializer<out AbstractContentComponentData>>
    val dataKClassMap: Map<String, KClass<out AbstractContentComponentData>>
}
