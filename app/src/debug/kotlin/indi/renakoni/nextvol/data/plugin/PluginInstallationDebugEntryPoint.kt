package indi.renakoni.nextvol.data.plugin

/** Supplies real host services to the isolated plugin installation device tests. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface PluginInstallationDebugEntryPoint {
    fun injector(): indi.renakoni.nextvol.data.plugin.injector.PluginInjector
    fun sources(): indi.renakoni.nextvol.data.web.WebBookDataSourceManager
    fun users(): indi.renakoni.nextvol.data.userdata.UserDataRepository
    fun networkSettings(): indi.renakoni.nextvol.data.web.SourceNetworkSettings
}
