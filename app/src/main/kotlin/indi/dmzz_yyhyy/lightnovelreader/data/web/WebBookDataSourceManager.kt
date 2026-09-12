package indi.dmzz_yyhyy.lightnovelreader.data.web

import dalvik.system.PathClassLoader
import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjector
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class WebBookDataSourceManager @Inject constructor (
    val registry: WebSourceRegistry,
): WebBookDataSourceManagerApi {
    private val registrationsByPackage = mutableMapOf<String, List<SourceRegistration>>()
    val webDataSourceItems: List<WebDataSourceItem> get() = registry.sources.value.map { it.metadata.item }

    override fun registerWebDataSource(webBookDataSource: WebBookDataSource, webDataSourceItem: WebDataSourceItem) {
        register(webBookDataSource, webDataSourceItem, builtIn = false)
    }

    private fun register(source: WebBookDataSource, item: WebDataSourceItem, builtIn: Boolean): SourceRegistration {
        val registration = registry.register(source, SourceMetadata(item, buildSet {
            addAll(setOf(
            SourceCapability.Search, SourceCapability.BookInformation, SourceCapability.Directory,
            SourceCapability.ChapterContent, SourceCapability.Images,
            ))
            if (source.discoveryProvider?.hasFeed == true) add(SourceCapability.Explore)
            if (source.discoveryProvider?.hasCategories == true) add(SourceCapability.Categories)
        }, builtIn))
        return registration
    }

    override fun unregisterWebDataSource(webDataSourceId: Identifier) {
        registry.unregister(webDataSourceId)
    }

    fun loadWebDataSourcesFromClassLoader(classLoader: PathClassLoader, injector: PluginInjector, packageName: String, webDataSourceClassNames: List<String>) {
        val items = mutableListOf<SourceRegistration>()
        try {
            webDataSourceClassNames.forEach { className ->
                val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
                if (!WebBookDataSource::class.java.isAssignableFrom(clazz)) return@forEach
                val instance = injector.provide<WebBookDataSource>(clazz)
                if (instance is WebBookDataSource) items.add(loadWebDataSourceClass(instance))
            }
        } catch (failure: Throwable) {
            items.asReversed().forEach { it.unregister() }
            throw failure
        }
        registrationsByPackage[packageName] = items
    }

    fun <T: WebBookDataSource>loadWebDataSourceFromClass(clazz: Class<T>, injector: PluginInjector) {
        if (!WebBookDataSource::class.java.isAssignableFrom(clazz)) return
        val instance = injector.provide<WebBookDataSource>(clazz)
        if (instance is WebBookDataSource) {
            val item = loadWebDataSourceClass(instance, builtIn = true)
            val packageName = clazz.`package`?.name ?: return
            if (registrationsByPackage.contains(packageName)) {
                registrationsByPackage[packageName] = registrationsByPackage[packageName]!! + listOf(item)
            } else {
                registrationsByPackage[packageName] = listOf(item)
            }
        }
    }

    private fun loadWebDataSourceClass(instance: WebBookDataSource, builtIn: Boolean = false): SourceRegistration {
        val info = instance.javaClass.getAnnotationsByType(WebDataSource::class.java)
        val item = WebDataSourceItem(
            instance.id,
            info.first().name,
            info.first().provider,
        )
        return register(instance, item, builtIn)
    }

    fun unloadWebDataSourcesFromClassLoader(packageName: String) {
        registrationsByPackage.remove(packageName)?.forEach { it.unregister() }
    }
}
