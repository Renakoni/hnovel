package indi.dmzz_yyhyy.lightnovelreader.data.web

import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceDiscoveryTest {
    private suspend fun WebSourceRegistry.add(name: String, provider: DiscoveryProvider): SourceRuntime {
        val id = Identifier("fixture", name)
        register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val discoveryProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, "Same name", "fixture"), buildSet {
            if (provider.hasFeed) add(SourceCapability.Explore)
            if (provider.hasCategories) add(SourceCapability.Categories)
        }))
        return (resolve(id) as SourceResolution.Ready).runtime
    }

    @Test fun nativeAndRuleAdaptersBindBooksAndTargetsToTheirOwnSource() = runBlocking {
        val registry = WebSourceRegistry()
        val rawBooks = mutableListOf(DiscoveryBook("1", "Same title", "Same author"))
        val provider = object : DiscoveryProvider {
            override val hasFeed = true
            override val hasCategories = true
            override suspend fun feed() = Ok(listOf(DiscoverySection("home", "Home", rawBooks, "all")))
            override suspend fun categories() = Ok(listOf(DiscoveryCategory("tag", "Tag", "all")))
        }
        val a = registry.add("native", provider)
        val b = registry.add("rules", provider)
        try {
            val af = a.discovery!!.feed().get()!!.single()
            val bf = b.discovery!!.feed().get()!!.single()
            assertNotEquals(af.books.single().id.storageKey, bf.books.single().id.storageKey)
            assertEquals(a.id, af.more!!.sourceId)
            assertEquals(b.id, b.discovery!!.categories().get()!!.single().target.sourceId)
            assertThrows(IllegalArgumentException::class.java) { a.discovery!!.open(bf.more!!) }
            rawBooks.clear()
            assertEquals(1, af.books.size)
        } finally { registry.unregister(a.id); registry.unregister(b.id) }
    }

    @Test fun pageSessionsOwnFiltersCursorRetryAndStableSnapshots() = runBlocking {
        val registry = WebSourceRegistry()
        val calls = mutableListOf<DiscoveryRequest>()
        var fail = true
        val provider = object : DiscoveryProvider {
            override val hasCategories = true
            override suspend fun page(request: DiscoveryRequest): com.github.michaelbull.result.Result<DiscoveryPage, DiscoveryError> {
                calls.add(request)
                if (request.cursor == "2" && fail) { fail = false; return Err(DiscoveryError.Network) }
                return Ok(DiscoveryPage(listOf(DiscoveryBook(request.cursor ?: "1", request.filters["sort"].orEmpty())),
                    if (request.cursor == null) "2" else null))
            }
        }
        val runtime = registry.add("rules", provider)
        try {
            val target = SourceDiscoveryTarget(runtime.id, "tag")
            val a = runtime.discovery!!.open(target)
            val b = runtime.discovery!!.open(target)
            val input = mutableMapOf("sort" to "popular")
            a.reset(input); input["sort"] = "mutated"
            b.reset(mapOf("sort" to "updated"))
            val first = a.loadMore().get()!!
            assertEquals("popular", first.books.single().title)
            assertEquals("updated", b.loadMore().get()!!.books.single().title)
            assertEquals(Err(DiscoveryError.Network), a.loadMore())
            assertEquals(2, a.loadMore().get()!!.books.size)
            val count = calls.size
            a.loadMore()
            assertEquals(count, calls.size)
            assertEquals(1, first.books.size)
            assertEquals(listOf(null, null, "2", "2"), calls.map { it.cursor })
            a.reset()
            assertEquals(1, a.loadMore().get()!!.books.size)
        } finally { registry.unregister(runtime.id) }
    }

    @Test fun absentCapabilityAndLoginRequirementRemainDistinct() = runBlocking {
        val registry = WebSourceRegistry()
        val runtime = registry.add("rules", object : DiscoveryProvider {
            override val hasCategories = true
            override suspend fun categories() = Err(DiscoveryError.AuthenticationRequired)
        })
        try {
            assertFalse(runtime.discovery!!.hasFeed)
            assertEquals(Err(DiscoveryError.Unsupported), runtime.discovery!!.feed())
            assertEquals(Err(DiscoveryError.AuthenticationRequired), runtime.discovery!!.categories())
        } finally { registry.unregister(runtime.id) }
    }

    @Test(timeout = 10000) fun removalCancelsDiscoveryAndOldPageCannotUseReplacement() = runBlocking {
        val registry = WebSourceRegistry()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val runtime = registry.add("rules", object : DiscoveryProvider {
            override val hasFeed = true
            override suspend fun feed(): com.github.michaelbull.result.Result<List<DiscoverySection>, DiscoveryError> {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        })
        val pending = async { runtime.discovery!!.feed() }
        started.await()
        registry.unregister(runtime.id)
        withTimeout(3000) { cancelled.await(); pending.join() }
        val replacement = registry.add("rules", object : DiscoveryProvider {})
        try {
            assertThrows(SourceUnavailableException::class.java) { runBlocking { runtime.discovery!!.feed() } }
            Unit
        } finally { registry.unregister(replacement.id) }
    }
}
