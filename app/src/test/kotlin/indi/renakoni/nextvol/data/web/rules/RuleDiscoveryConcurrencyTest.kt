package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import hnovel.content.RuleDiscoverySession
import hnovel.content.RuleListSession
import io.mockk.coEvery
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleDiscoveryConcurrencyTest {
    private fun definition(raw: JsonObject, urls: List<String> = listOf("/one", "/two", "/three", "/four")) = JsonObject(raw + mapOf(
        "homepageModules" to JsonPrimitive(buildJsonArray { urls.forEachIndexed { index, url -> add(buildJsonObject {
            put("key", "module-$index"); put("type", "ranking"); put("title", "Module $index"); put("url", url)
        }) } }.toString()),
        "ruleExplore" to buildJsonObject { put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href") }
    ))
    private fun html(path: String) = (1..30).joinToString("") { "<li><h2>$path $it</h2><a href='/book/$it'>Read</a></li>" }

    @Test fun aCancelledSourceReadEndsTheFeedInsteadOfWaitingForAMissingResult() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { definition(it, listOf("/one", "/two")) }.use { source ->
            val catalog = source.openDiscovery("catalog").catalog(homepage = true)
            val first = mockk<RuleListSession> { coEvery { page(1) } throws CancellationException("Source retired") }
            val second = mockk<RuleListSession> { coEvery { page(1) } coAnswers { awaitCancellation() } }
            val session = mockk<RuleDiscoverySession> {
                coEvery { catalog(homepage = true) } returns catalog
                coEvery { concurrentPreviews(any(), any()) } returns listOf(first, second)
            }
            val failed = withTimeout(2000) { runCatching { RuleDiscoveryProvider(source, session).feed() }.exceptionOrNull() }
            assertTrue(failed is CancellationException && failed !is TimeoutCancellationException)
        } }
    }

    @Test fun blockedScriptSourceModuleDoesNotHoldOthersAndLoginStateStaysInItsOwnSlot() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val release = CountDownLatch(1)
            val firstEntered = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    try {
                        if (request.path == "/one") { firstEntered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                        else check(firstEntered.await(5, TimeUnit.SECONDS))
                        return MockResponse().setBody(html(request.path!!))
                    } finally { active.decrementAndGet() }
                }
            }
            fixture.source { raw -> JsonObject(definition(raw) + mapOf(
                "jsLib" to JsonPrimitive("function previewUrl(value){return value}"),
                "loginCheckJs" to JsonPrimitive("java.put('preview-url',previewUrl(baseUrl));result"),
                "ruleExplore" to buildJsonObject {
                    put("bookList", "@js:if(java.get('preview-url')!==baseUrl)throw 'wrong preview';java.getElements('li')")
                    put("name", "h2@text"); put("bookUrl", "a@href")
                }
            )) }.use { source ->
                val updates = Channel<List<DiscoverySection>>(Channel.UNLIMITED)
                val pending = launch { RuleDiscoveryProvider(source).feedUpdates().collect { updates.send(requireNotNull(it.get())) } }
                try {
                    var snapshot = withTimeout(10000) { updates.receive() }
                    assertTrue(snapshot.all { it.previewLoading && it.books.isEmpty() })
                    withTimeout(10000) { while (snapshot.drop(1).any { it.previewLoading }) snapshot = updates.receive() }
                    assertEquals(listOf("Module 0", "Module 1", "Module 2", "Module 3"), snapshot.map { it.title })
                    assertTrue(snapshot.first().previewLoading)
                    assertTrue(snapshot.drop(1).all { it.books.size == 6 && it.previewFailure == null })
                    assertTrue(maximum.get() in 2..RuleDiscoveryProvider.PREVIEW_CONCURRENCY)
                    assertEquals(4, fixture.server.requestCount)
                } finally { release.countDown() }
                pending.join()
                assertTrue(updates.receive().all { it.books.size == 6 && !it.previewLoading })
            }
        }
    }

    @Test fun localFailureAndReadySectionPublishBeforeASlowScriptSourceSection() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/slow") {
                        entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    } else check(entered.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody(html(request.path!!))
                }
            }
            fixture.source { raw -> JsonObject(definition(raw, listOf(
                "/slow", "/invalid,{\"method\":\"INVALID\"}", "/ready")) +
                ("jsLib" to JsonPrimitive("var helper=1"))) }.use { source ->
                val updates = Channel<List<DiscoverySection>>(Channel.UNLIMITED)
                val pending = launch { RuleDiscoveryProvider(source).feedUpdates().collect { updates.send(requireNotNull(it.get())) } }
                try {
                    var snapshot = withTimeout(10000) { updates.receive() }
                    withTimeout(10000) { while (snapshot.drop(1).any { it.previewLoading }) snapshot = updates.receive() }
                    assertTrue(snapshot.first().previewLoading)
                    assertNotNull(snapshot[1].previewFailure)
                    assertEquals(6, snapshot[2].books.size)
                    assertEquals(2, fixture.server.requestCount)
                } finally { release.countDown(); pending.cancelAndJoin() }
            }
        }
    }

    @Test fun parallelFailuresRemainLocalAndRetryDoesNotReloadSuccessfulModules() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val visits = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
            var failed = true
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    visits.computeIfAbsent(request.path!!) { AtomicInteger() }.incrementAndGet()
                    return if (request.path == "/two" && failed) MockResponse().setResponseCode(503)
                        else MockResponse().setBody(html(request.path!!))
                }
            }
            fixture.source { definition(it, listOf("https://ungranted.test/list", "/two", "/three")) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val feed = requireNotNull(provider.feed().get())
                assertEquals(DiscoveryError.PermissionDenied, feed[0].previewFailure?.error)
                assertEquals(DiscoveryError.Network, feed[1].previewFailure?.error)
                assertEquals(6, feed[2].books.size)
                assertNull(feed[2].previewFailure)
                assertNull(provider.failureField)
                assertNull(provider.permissionFailure)
                failed = false
                val retried = requireNotNull(provider.preview(feed[1].id).get())
                assertNull(retried.previewFailure)
                assertEquals(6, retried.books.size)
                assertEquals(2, visits["/two"]?.get())
                assertEquals(1, visits["/three"]?.get())
            }
        }
    }
}
