package indi.renakoni.nextvol.data.content

import indi.renakoni.nextvol.data.plugin.injector.PluginInjectorProvider
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/** Adapts decoded component data to the existing plugin reflection and injection contract. */
@Singleton
class ContentComponentFactory @Inject constructor(
    private val pluginInjectorProvider: PluginInjectorProvider,
) {
    internal fun create(
        componentClass: KClass<out AbstractContentComponent<out AbstractContentComponentData>>,
        dataClass: KClass<out AbstractContentComponentData>,
        decodeData: () -> AbstractContentComponentData,
    ): AbstractContentComponent<out AbstractContentComponentData>? {
        val injector = pluginInjectorProvider.value ?: return null
        return injector.provide(
            componentClass.java,
            injector.injectMap.toMutableMap().apply {
                put(dataClass.java, decodeData())
            },
        )
    }
}
