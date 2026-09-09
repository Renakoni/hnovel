package indi.dmzz_yyhyy.lightnovelreader.data.web

import android.app.Application
import com.github.michaelbull.result.getError
import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjector
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.StringUserData
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceManagerBindingTest {
    private fun manager(id: Identifier): WebBookDataSourceManager {
        val repository = mockk<UserDataRepository>()
        val selection = mockk<StringUserData>()
        every { repository.stringUserData(any()) } returns selection
        coEvery { selection.get() } returns id.toString()
        return WebBookDataSourceManager(repository, WebSourceRegistry())
    }

    @Test(timeout = 10000)
    fun listRefreshReusesTheBoundRuntimeAndPackageRemovalInvalidatesIt() = runBlocking {
        val source = BuiltInFixture()
        val manager = manager(source.id)
        val injector = mockk<PluginInjector>()
        every { injector.provide<WebBookDataSource>(BuiltInFixture::class.java) } returns source
        manager.loadWebDataSourceFromClass(BuiltInFixture::class.java, injector)
        val bound = manager.getWebDataSourceProvider().value
        val oldSource = manager.getWebDataSource()
        val other = object : WebBookDataSource by EmptyWebDataSource {
            override val id = Identifier("fixture", "other")
        }
        try {
            manager.registerWebDataSource(other, WebDataSourceItem(other.id, "Other", "fixture"))
            manager.onWebDataSourceListChange()
            oldSource.onLoad()
            assertSame(bound, manager.getWebDataSourceProvider().value)
            assertEquals(1, source.loads.get())
            assertTrue(manager.registry.sources.value.first().metadata.builtIn)
            manager.unregisterWebDataSource(other.id)
            manager.unloadWebDataSourcesFromClassLoader(requireNotNull(BuiltInFixture::class.java.`package`).name)
            assertTrue(manager.webDataSourceItems.isEmpty())
            assertTrue(manager.registry.resolve(source.id) is SourceResolution.Missing)
            assertFalse(manager.getWebDataSourceProvider().isWebDataSourceFounded())
            assertThrows(SourceUnavailableException::class.java) { oldSource.onLoad() }
            withTimeout(3000) { source.closed.await() }
            assertEquals(1, source.closes.get())
        } finally {
            manager.unregisterWebDataSource(source.id)
            manager.unregisterWebDataSource(other.id)
        }
    }

    @Test(timeout = 10000)
    fun unavailableSelectionDoesNotChooseAnotherRegisteredSource() = runBlocking {
        val missing = Identifier("fixture", "missing")
        val manager = manager(missing)
        val source = BuiltInFixture()
        manager.registerWebDataSource(source, WebDataSourceItem(source.id, "Available", "fixture"))
        try {
            assertFalse(manager.getWebDataSourceProvider().isWebDataSourceFounded())
            assertEquals(missing, manager.getWebDataSource().id)
            assertEquals(0, source.loads.get())
            assertNotNull(manager.getWebDataSource().getBookVolumes("book").getError())
        } finally { manager.unregisterWebDataSource(source.id) }
    }

    @WebDataSource("Fixture", "Built-in fixture")
    class BuiltInFixture : WebBookDataSource by EmptyWebDataSource, AutoCloseable {
        override val id = Identifier("fixture", "builtin")
        override val permits = 2
        val loads = AtomicInteger()
        val closes = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        override fun onLoad() { loads.incrementAndGet() }
        override fun close() { closes.incrementAndGet(); closed.complete(Unit) }
    }

    @Test(timeout = 10000)
    fun oldBindingCompletionAndOldRegistrationOwnerCannotReplaceOrRemoveTheNewSource() = runBlocking {
        val id = Identifier("fixture", "same")
        val manager = manager(id)
        val metadata = SourceMetadata(WebDataSourceItem(id, "Source", "fixture"), emptySet())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = manager.registry.register(metadata) {
            object : WebBookDataSource by EmptyWebDataSource {
                override val id = metadata.id
                override fun onLoad() = runBlocking { started.complete(Unit); release.await() }
            }
        }
        val oldBinding = async(Dispatchers.IO) { manager.onWebDataSourceListChange() }
        try {
            started.await()
            old.unregister()
            manager.registry.register(metadata) {
                object : WebBookDataSource by EmptyWebDataSource { override val id = metadata.id }
            }
            manager.onWebDataSourceListChange()
            val current = manager.getWebDataSourceProvider().value
            release.complete(Unit)
            oldBinding.await()
            old.unregister()
            assertSame(current, manager.getWebDataSourceProvider().value)
            assertTrue(manager.registry.resolve(id) is SourceResolution.Ready)
        } finally { release.complete(Unit); manager.unregisterWebDataSource(id) }
    }
}
