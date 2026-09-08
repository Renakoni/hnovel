package indi.dmzz_yyhyy.lightnovelreader.data.content

import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjector
import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjectorProvider
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentRenderer
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
