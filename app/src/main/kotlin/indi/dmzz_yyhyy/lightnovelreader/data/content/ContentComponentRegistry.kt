package indi.dmzz_yyhyy.lightnovelreader.data.content

import indi.dmzz_yyhyy.lightnovelreader.data.content.component.ImageComponent
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.SimpleTextComponent
import io.nightfish.lightnovelreader.api.content.ContentComponentRepositoryApi
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ComponentDataJsonElementSerializer
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/** Owns built-in and plugin registrations, without constructing components or resolving host services. */
@Singleton
class ContentComponentRegistry @Inject constructor() : ComponentDataRegistry {
    private val serializeMutableMap = mutableMapOf<String, ComponentDataJsonElementSerializer<out AbstractContentComponentData>>()
    override val serializeMap get() = serializeMutableMap.toMap()
    private val kClassMutableMap = mutableMapOf<String, KClass<out AbstractContentComponent<out AbstractContentComponentData>>>()
    private val dataKClassMutableMap = mutableMapOf<String, KClass<out AbstractContentComponentData>>()
    override val dataKClassMap get() = dataKClassMutableMap.toMap()

    internal fun componentClass(id: String) = kClassMutableMap[id]
    internal fun dataClass(id: String) = dataKClassMutableMap[id]
    internal fun serializer(id: String) = serializeMutableMap[id]

    val registrar: ContentComponentRepositoryApi.Registrar = object : ContentComponentRepositoryApi.Registrar {
        override fun id(id: Identifier) = RegisterBuilder(id)
    }

    private inner class RegisterBuilder(val id: Identifier) : ContentComponentRepositoryApi.RegisterBuilder {
        private var componentKClass: KClass<out AbstractContentComponent<out AbstractContentComponentData>>? = null
        private var componentDataKClass: KClass<out AbstractContentComponentData>? = null
        private var serializer: ComponentDataJsonElementSerializer<out AbstractContentComponentData>? = null

        override fun component(value: KClass<out AbstractContentComponent<out AbstractContentComponentData>>): RegisterBuilder {
            componentKClass = value
            return this
        }

        override fun data(value: KClass<out AbstractContentComponentData>): RegisterBuilder {
            componentDataKClass = value
            return this
        }

        override fun serializer(value: ComponentDataJsonElementSerializer<out AbstractContentComponentData>): RegisterBuilder {
            serializer = value
            return this
        }

        override fun register() {
            if (componentKClass == null || componentDataKClass == null || serializer == null) throw Error("builder missing parameters")
            kClassMutableMap[id.toString()] = componentKClass!!
            dataKClassMutableMap[id.toString()] = componentDataKClass!!
            serializeMutableMap[id.toString()] = serializer!!
        }
    }

    init {
        registrar
            .id(SimpleTextComponentData.id)
            .component(SimpleTextComponent::class)
            .data(SimpleTextComponentData::class)
            .serializer(SimpleTextComponentData.jsonSerializer)
            .register()

        registrar
            .id(ImageComponentData.id)
            .component(ImageComponent::class)
            .data(ImageComponentData::class)
            .serializer(ImageComponentData.jsonSerializer)
            .register()
    }
}
