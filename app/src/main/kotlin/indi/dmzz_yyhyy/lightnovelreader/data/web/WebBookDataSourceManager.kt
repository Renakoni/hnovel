package indi.dmzz_yyhyy.lightnovelreader.data.web

import dalvik.system.PathClassLoader
import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjector
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.utils.convertOldId
import indi.dmzz_yyhyy.lightnovelreader.utils.ofId
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class WebBookDataSourceManager @Inject constructor (
    val userDataRepository: UserDataRepository,
    val registry: WebSourceRegistry,
): WebBookDataSourceManagerApi {
    private val registrationsByPackage = mutableMapOf<String, List<SourceRegistration>>()
    private val bindingLock = Any()
    private var bindingGeneration = 0L
    val webDataSourceItems: List<WebDataSourceItem> get() = registry.sources.value.map { it.metadata.item }

    private val mutableWebDataSourceProvider = MutableWebDataSourceProvider()

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
        onWebDataSourceListChange()
        return registration
    }

    override fun unregisterWebDataSource(webDataSourceId: Identifier) {
        registry.unregister(webDataSourceId)
        onWebDataSourceListChange()
    }

    override fun getWebDataSource(): WebBookDataSource = mutableWebDataSourceProvider.value.origin

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
            onWebDataSourceListChange()
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
        onWebDataSourceListChange()
    }

    fun getWebDataSourceProvider(): WebBookDataSourceProvider {
        return mutableWebDataSourceProvider
    }

    fun onWebDataSourceListChange() = runBlocking {
        val generation = synchronized(bindingLock) { ++bindingGeneration }
        val webDataSourcesId = userDataRepository
            .stringUserData(UserDataPath.Settings.Data.WebDataSourceId.path)
            .get()
            ?.convertOldId() ?: "Wenku8".ofId()
        // Temporary single-source binding. Browsing/import code must resolve the
        // registry by ID instead; refreshing this facade does not reload a runtime.
        val resolved = registry.resolve(webDataSourcesId)
        synchronized(bindingLock) {
            if (generation == bindingGeneration) when (resolved) {
                is SourceResolution.Ready -> mutableWebDataSourceProvider.bind(resolved.runtime)
                is SourceResolution.Missing -> mutableWebDataSourceProvider.unavailable(NotFoundWebDataSource(webDataSourcesId))
                is SourceResolution.Unavailable -> mutableWebDataSourceProvider.unavailable(NotFoundWebDataSource(webDataSourcesId, initializationFailed = true))
            }
        }
    }
}
