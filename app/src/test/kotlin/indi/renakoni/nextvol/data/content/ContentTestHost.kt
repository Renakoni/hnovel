package indi.renakoni.nextvol.data.content

import indi.renakoni.nextvol.data.plugin.injector.PluginInjector
import indi.renakoni.nextvol.data.plugin.injector.PluginInjectorProvider
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.book.reader.content.ContentRenderer
import io.mockk.mockk
import org.robolectric.RuntimeEnvironment

internal class ContentTestHost {
    val provider = PluginInjectorProvider()
    val registry = ContentComponentRegistry()
    val decoder = ContentJsonDecoder(registry)
    val renderer = ContentRenderer(decoder, ContentComponentFactory(provider))
    val repository = ContentComponentRepository(registry)
    val settings = mockk<UserDataRepository>(relaxed = true)

    fun initializeInjector(settings: UserDataRepository = this.settings): PluginInjector = PluginInjector(
        provider, RuntimeEnvironment.getApplication(), mockk(), settings, mockk(),
        mockk(), mockk(), mockk(), mockk(), repository, mockk(), mockk(),
    )
}
