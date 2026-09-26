package indi.renakoni.nextvol.sourceexecution

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import hnovel.content.ContentTrace
import hnovel.content.RuleSource
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionTask
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.data.web.AndroidSourceStorageCipher
import indi.renakoni.nextvol.data.web.rules.RuleDiscoveryProvider
import indi.renakoni.nextvol.di.WebDataSourceModule
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import io.nightfish.lightnovelreader.api.web.discovery.DiscoverySection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, fixed local pages through the real provider, Binder worker, storage and browser. */
@RunWith(AndroidJUnit4::class)
class DiscoveryPerformanceInstrumentedTest {
    private val titles = listOf("Daily", "Weekly", "Monthly", "Articles")
    private val entries = 30

    @Test fun compareFourSectionLoads(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("discoveryPerformance") == "true")
        val repeats = (args.getString("discoveryRepeats") ?: "5").toInt().also { require(it in 1..10) }
        val modes = (args.getString("discoveryModes") ?: "http,native").split(',')
        require(modes.isNotEmpty() && modes.all { it == "http" || it == "native" })
        val context = instrumentation.targetContext
        for (mode in modes) MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    val body = when {
                        path.startsWith("/list/") -> {
                            val section = path.substringAfterLast('/').toInt()
                            (1..entries).joinToString("") { index ->
                                "<li class='book'><a class='title' href='/book/$section/$index'>Book $section-$index</a>" +
                                    "<b class='author'>Author $index</b><p class='intro'>Description $index</p></li>"
                            }.let { "<ul>$it</ul>" }
                        }
                        path.startsWith("/book/") -> {
                            val parts = path.split('/')
                            "<h1>Book ${parts[2]}-${parts[3]}</h1><b class='author'>Author ${parts[3]}</b>" +
                                "<span class='latest'>Latest chapter</span>"
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Cache-Control", "no-store")
                        .setBody("<html><head><link rel='icon' href='data:,'></head><body>$body</body></html>")
                }
            }
            server.start()
            val root = File(context.cacheDir, "discovery-performance-$mode-${System.nanoTime()}")
            val authority = ExecutionAuthority()
            val executor = AndroidIsolatedExecutor(context, authority)
            val taskRunner = WebDataSourceModule.provideRuleTaskRunner(executor)
            val calls = ConcurrentHashMap<String, AtomicInteger>()
            val taskNanos = ConcurrentHashMap<String, AtomicLong>()
            val executionNanos = AtomicLong()
            val networkMillis = AtomicLong()
            val runner = RuleTaskRunner { owner, task, limits, bridge ->
                val field = (task as? ExecutionTask.Rule)?.location?.field ?: task.javaClass.simpleName
                calls.computeIfAbsent(field) { AtomicInteger() }.incrementAndGet()
                val started = SystemClock.elapsedRealtimeNanos()
                try { taskRunner.execute(owner, task, limits, bridge) }
                finally {
                    val elapsed = SystemClock.elapsedRealtimeNanos() - started
                    executionNanos.addAndGet(elapsed)
                    taskNanos.computeIfAbsent(field) { AtomicLong() }.addAndGet(elapsed)
                }
            }
            val trace = ContentTrace { event ->
                if (event.kind == "network") networkMillis.addAndGet(event.elapsedMillis)
            }
            try {
                val definitions = SourceDefinitionStore(File(root, "definitions").toPath())
                val importer = SourceDefinitionImporter(definitions)
                val raw = buildJsonObject {
                    put("bookSourceUrl", server.url("/").toString())
                    put("bookSourceName", "Discovery performance fixture")
                    put("bookSourceType", 0); put("enabledExplore", true); put("browserRead", mode == "native")
                    put("homepageModules", buildJsonArray {
                        titles.forEachIndexed { index, title -> add(buildJsonObject {
                            put("key", "section-$index"); put("type", "ranking"); put("title", title)
                            put("url", "/list/$index?page={{page}}")
                        }) }
                    }.toString())
                    put("ruleExplore", buildJsonObject {
                        put("bookList", "@js:java.getElements('li.book')")
                        put("name", "a.title@text"); put("author", "b.author@text")
                        put("intro", "p.intro@text"); put("bookUrl", "a.title@href")
                    })
                    put("ruleBookInfo", buildJsonObject {
                        put("name", "h1@text"); put("author", "b.author@text"); put("lastChapter", "span.latest@text")
                    })
                }
                val preview = importer.preview(raw.toString(), LEGADO_PROFILE)
                assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                assertNull(importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error)
                val definition = definitions.list().single()
                SourceBroker(File(root, "broker").toPath(), cipher = AndroidSourceStorageCipher(),
                    browser = AndroidSourceBrowser(context)).use { broker ->
                    val session = broker.open(SourceScope("discovery-performance", definition.sourceId, definition.profile),
                        listOf(NetworkGrant(server.url("/").toString(), allowPrivateAddresses = true)))
                    val identity = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "discovery-performance")
                    try { RuleSource(definition, identity, authority, session, runner, trace).use { source ->
                        var completed = emptyList<DiscoverySection>()
                        for (iteration in 0..repeats) {
                            calls.clear(); taskNanos.clear(); executionNanos.set(0); networkMillis.set(0)
                            val requestsBefore = server.requestCount
                            val provider = RuleDiscoveryProvider(source, source.openDiscovery("iteration-$iteration"))
                            val ready = linkedMapOf<String, Double>()
                            val started = SystemClock.elapsedRealtimeNanos()
                            withTimeout(180_000) {
                                provider.feedUpdates().collect { result ->
                                    val sections = result.get() ?: error("Discovery failed: ${result.getError()}")
                                    sections.forEach { section ->
                                        assertNull(section.previewFailure)
                                        if (section.books.isNotEmpty()) {
                                            assertEquals(6, section.books.size)
                                            ready.putIfAbsent(section.title, (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                                        }
                                    }
                                    completed = sections
                                }
                            }
                            val totalMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                            assertEquals(titles, completed.map { it.title })
                            completed.forEachIndexed { sectionIndex, section ->
                                assertEquals((1..6).map { "Book $sectionIndex-$it" }, section.books.map { it.title })
                                assertEquals((1..6).map { server.url("/book/$sectionIndex/$it").toString() }, section.books.map { it.remoteId })
                            }
                            assertEquals(4, ready.size)
                            assertEquals(4, server.requestCount - requestsBefore)
                            val report = buildJsonObject {
                                put("label", args.getString("discoveryLabel") ?: "local")
                                put("mode", mode); put("iteration", iteration); put("sample", if (iteration == 0) "first" else "repeat")
                                put("booksPerPage", entries); put("previewBooks", completed.sumOf { it.books.size })
                                put("firstBooksMs", ready.values.min()); put("allBooksMs", totalMillis)
                                put("sectionReadyMs", buildJsonObject { ready.forEach { (name, elapsed) -> put(name, elapsed) } })
                                put("requests", server.requestCount - requestsBefore)
                                put("ruleTasks", calls.values.sumOf { it.get() })
                                put("taskCounts", buildJsonObject { calls.toSortedMap().forEach { (field, count) -> put(field, count.get()) } })
                                put("taskMillis", buildJsonObject { taskNanos.toSortedMap().forEach { (field, nanos) -> put(field, nanos.get() / 1_000_000.0) } })
                                put("executionMs", executionNanos.get() / 1_000_000.0)
                                put("networkMs", networkMillis.get())
                                put("unattributedMs", totalMillis - executionNanos.get() / 1_000_000.0 - networkMillis.get())
                            }
                            instrumentation.sendStatus(0, Bundle().apply { putString("discoveryPerformance", report.toString()) })
                        }
                        // Check the ordinary open-book path after the timed runs, without warming it between samples.
                        val first = completed.first().books.first()
                        val information = source.information(first.remoteId)
                        assertEquals(first.title, information.title)
                        assertEquals(first.author, information.author)
                    } } finally { session.clearAccount() }
                }
            } finally { executor.close(); root.deleteRecursively() }
        }
    }
}
