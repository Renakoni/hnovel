package indi.renakoni.nextvol.data.web

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
    val webDataSourceItems: List<WebDataSourceItem> get() = registry.sources.value.map { it.metadata.item }

    override fun registerWebDataSource(webBookDataSource: WebBookDataSource, webDataSourceItem: WebDataSourceItem) {
        register(webBookDataSource, webDataSourceItem, builtIn = false)
    }

    private fun register(source: WebBookDataSource, item: WebDataSourceItem, builtIn: Boolean,
        category: SourceCategory? = null): SourceRegistration {
        val registration = registry.register(source, SourceMetadata(item, buildSet {
            addAll(setOf(
            SourceCapability.Search, SourceCapability.BookInformation, SourceCapability.Directory,
            SourceCapability.ChapterContent, SourceCapability.Images,
            ))
            if (source.discoveryProvider?.hasFeed == true) add(SourceCapability.Explore)
            if (source.discoveryProvider?.hasCategories == true) add(SourceCapability.Categories)
        }, builtIn, category = category))
        return registration
    }

    override fun unregisterWebDataSource(webDataSourceId: Identifier) {
        registry.unregister(webDataSourceId)
    }

    fun loadBuiltInSource(instance: WebBookDataSource, category: SourceCategory? = null) {
        loadWebDataSourceClass(instance, builtIn = true, category = category)
    }

    private fun loadWebDataSourceClass(instance: WebBookDataSource, builtIn: Boolean = false,
        category: SourceCategory? = null): SourceRegistration {
        val info = instance.javaClass.getAnnotationsByType(WebDataSource::class.java)
        val item = WebDataSourceItem(
            instance.id,
            info.first().name,
            info.first().provider,
        )
        return register(instance, item, builtIn, category)
    }

}
