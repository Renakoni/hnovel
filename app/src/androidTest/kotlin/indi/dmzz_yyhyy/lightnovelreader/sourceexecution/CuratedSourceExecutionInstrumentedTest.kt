package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.content.*
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CuratedSourceExecutionInstrumentedTest {
    /** Fresh public DNS is an explicit test control, never a production address-policy exception. */
    @Test fun curatedSitesReachSearchDirectoriesAndNovelTextAcrossBinder() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveCuratedSources") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val mappings = Json.parseToJsonElement(String(Base64.decode(args.getString("curatedSourcesDnsBase64"), Base64.DEFAULT), Charsets.UTF_8)).jsonObject
        val dns = Dns { host -> mappings[host]?.jsonArray?.map { InetAddress.getByName(it.jsonPrimitive.content) }
            ?.takeIf { it.isNotEmpty() } ?: throw UnknownHostException("No fresh test-control DNS") }
        val root = File(context.cacheDir, "curated-live-${UUID.randomUUID()}").apply { mkdirs() }
        val definitions = SourceDefinitionStore(File(root, "definitions").toPath())
        val importer = SourceDefinitionImporter(definitions)
        val text = checkNotNull(SourceDefinitionImporter::class.java.getResourceAsStream("/curated-sources/novels-v1.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val preview = importer.preview(text)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertNull(importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) }).error)
        val samples = mapOf(
            "https://fanqienovel.com/" to listOf(
                Triple("西游记", "page/6838936304063876104", "reader/6839879711863079437"),
                Triple("修仙", "page/7117541469996452867", "reader/7117544730275250695")),
            "https://book.sfacg.com/" to listOf(Triple("魔女", "Novel/292551/", "https://m.sfacg.com/c/3644868/")),
            "https://www.kanunu8.com/" to listOf(
                Triple("西游记", "files/old/2011/2448.html", "files/old/2011/2448/71898.html"),
                Triple("三国演义", "files/old/2011/2447.html", "files/old/2011/2447/71775.html")))
        val failures = mutableListOf<String>()
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        try {
            assertEquals(samples.keys, definitions.list().map { it.importKey }.toSet())
            for (definition in definitions.list()) {
                val authority = ExecutionAuthority()
                val executor = AndroidIsolatedExecutor(context, authority)
                val broker = SourceBroker(File(root, definition.sourceId).toPath(), dns = dns)
                val origin = definition.importKey
                val origins = listOf(origin) + when (origin) {
                    "https://fanqienovel.com/" -> listOf("https://novel.snssdk.com/")
                    "https://book.sfacg.com/" -> listOf("https://m.sfacg.com/")
                    else -> emptyList()
                }
                val session = broker.open(SourceScope("curated-live", definition.sourceId, definition.profile), origins.map(::NetworkGrant))
                val identity = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "curated-live")
                val runner = RuleTaskRunner { ticket, task, limits, bridge -> executor.execute(ticket, task, limits, bridge) }
                val trace = mutableListOf<ContentTraceEvent>()
                val source = RuleSource(definition, identity, authority, session, runner, ContentTrace { trace += it })
                try {
                    if (source.canDiscover) {
                        val catalog = source.openDiscovery("live").catalog()
                        assertEquals(4, source.discovery(catalog.rows.single().url).size)
                    }
                    for ((keyword, path, chapterPath) in samples.getValue(origin)) {
                        val search = source.search(keyword)
                        assertTrue(search.isNotEmpty())
                        val bookId = origin + path
                        val info = source.information(bookId)
                        val chapters = source.directory(bookId).filterNot { it.isVolume }
                        val chapterUrl = if (chapterPath.startsWith("https://")) chapterPath else origin + chapterPath
                        val chapter = chapters.single { it.id == chapterUrl }
                        val content = source.content(bookId, chapter.id)
                        val plain = content.parts.joinToString("\n") { it.text.orEmpty() }
                        assertTrue(info.title.isNotBlank())
                        assertTrue(plain.length.toString(), plain.length > 1000)
                        assertTrue(content.parts.all { it.text != null })
                        assertFalse(plain.any { it.code in 0xe000..0xf8ff || it == '\ufffd' })
                        if (keyword == "西游记") assertTrue(plain.contains("混沌未分天地乱"))
                        if (keyword == "三国演义") assertTrue(plain.contains("滚滚长江东逝水"))
                        report("Curated $origin: search=${search.size}, chapters=${chapters.size}, text=${plain.length}, chapter=$chapterPath")
                    }
                } catch (failure: Throwable) {
                    val reason = (failure as? SourceContentException)?.let { "${it.code}:${it.field}" } ?: failure.javaClass.simpleName
                    failures += "$origin: $reason"
                    report("Curated $origin: $reason; trace=" + trace.takeLast(3).joinToString { "${it.field}:${it.result}:${it.ruleCode}" })
                } finally { source.close(); executor.close(); broker.close() }
            }
            assertEquals(failures.toString(), emptyList<String>(), failures)
        } finally { root.deleteRecursively() }
    }
}
