package indi.dmzz_yyhyy.lightnovelreader.data.web

import android.app.Application
import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjector
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.CompletableDeferred
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
class SourceManagerRegistrationTest {
    private fun WebBookDataSourceManager.load(source: BuiltInFixture) {
        val injector = mockk<PluginInjector>()
        every { injector.provide<WebBookDataSource>(BuiltInFixture::class.java) } returns source
        loadWebDataSourceFromClass(BuiltInFixture::class.java, injector)
    }

    @Test(timeout = 10000)
    fun registrationAndInventoryDoNotInitializeSourcesWhileExplicitResolutionReusesOneRuntime() = runBlocking {
        val manager = WebBookDataSourceManager(WebSourceRegistry())
        val source = BuiltInFixture()
        val other = BuiltInFixture(Identifier("fixture", "other"))
        manager.load(source)
        manager.registerWebDataSource(other, WebDataSourceItem(other.id, "Other", "fixture"))
        try {
            assertEquals(2, manager.webDataSourceItems.size)
            assertEquals(0, source.loads.get())
            assertEquals(0, other.loads.get())
            assertTrue(manager.registry.sources.value.first().metadata.builtIn)
            val runtime = (manager.registry.resolve(source.id) as SourceResolution.Ready).runtime
            assertSame(runtime, (manager.registry.resolve(source.id) as SourceResolution.Ready).runtime)
            assertEquals(1, source.loads.get())
            manager.unregisterWebDataSource(other.id)
            assertTrue(runtime.isAvailable)
            assertEquals(0, other.loads.get())
            manager.unloadWebDataSourcesFromClassLoader(requireNotNull(BuiltInFixture::class.java.`package`).name)
            assertTrue(manager.webDataSourceItems.isEmpty())
            assertFalse(runtime.isAvailable)
            withTimeout(3000) { source.closed.await(); other.closed.await() }
            assertEquals(1, source.closes.get())
            assertEquals(1, other.closes.get())
        } finally {
            manager.unregisterWebDataSource(source.id)
            manager.unregisterWebDataSource(other.id)
        }
    }

    @Test fun missingIdentityDoesNotInitializeAnotherRegisteredSource() = runBlocking {
        val manager = WebBookDataSourceManager(WebSourceRegistry())
        val source = BuiltInFixture()
        manager.load(source)
        try {
            assertTrue(manager.registry.resolve(Identifier("fixture", "missing")) is SourceResolution.Missing)
            assertEquals(0, source.loads.get())
        } finally { manager.unregisterWebDataSource(source.id) }
    }

    @Test(timeout = 10000)
    fun unloadingAnOldPackageDoesNotRemoveAReplacementWithTheSameIdentity() = runBlocking {
        val manager = WebBookDataSourceManager(WebSourceRegistry())
        val old = BuiltInFixture()
        manager.load(old)
        val oldRuntime = (manager.registry.resolve(old.id) as SourceResolution.Ready).runtime
        manager.unregisterWebDataSource(old.id)
        val replacement = BuiltInFixture()
        manager.registerWebDataSource(replacement, WebDataSourceItem(replacement.id, "Replacement", "fixture"))
        try {
            manager.unloadWebDataSourcesFromClassLoader(requireNotNull(BuiltInFixture::class.java.`package`).name)
            assertFalse(oldRuntime.isAvailable)
            assertEquals(1, manager.webDataSourceItems.size)
            assertTrue(manager.registry.resolve(replacement.id) is SourceResolution.Ready)
            assertEquals(1, replacement.loads.get())
            withTimeout(3000) { old.closed.await() }
            assertEquals(0, replacement.closes.get())
        } finally { manager.unregisterWebDataSource(replacement.id) }
    }

    @WebDataSource("Fixture", "Built-in fixture")
    class BuiltInFixture(override val id: Identifier = Identifier("fixture", "builtin")) :
        WebBookDataSource by EmptyWebDataSource, AutoCloseable {
        override val permits = 2
        val loads = AtomicInteger()
        val closes = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        override fun onLoad() { loads.incrementAndGet() }
        override fun close() { closes.incrementAndGet(); closed.complete(Unit) }
    }
}
