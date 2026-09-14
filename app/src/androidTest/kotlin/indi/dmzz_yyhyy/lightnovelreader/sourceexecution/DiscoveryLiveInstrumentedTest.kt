package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import hnovel.content.SourceContentException
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.dmzz_yyhyy.lightnovelreader.LightNovelReaderApplication
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.discovery
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.ImportedRuleSources
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.SourceVerificationDebugEntryPoint
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in device diagnostic. Input/grants are supplied locally; reports omit URLs, cookies and book text. */
@RunWith(AndroidJUnit4::class)
class DiscoveryLiveInstrumentedTest {
    @Test fun compareSearchCatalogAndHomepageInInstalledApp(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveDiscovery") == "true")
        val application = instrumentation.targetContext.applicationContext as LightNovelReaderApplication
        val sources = application.importedRuleSources
        sources.restore()
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(application, SourceVerificationDebugEntryPoint::class.java)
        val input = File(application.filesDir, "discovery-audit-input.json")
        require(input.length() in 1..2_000_000)
        val cases = Json.parseToJsonElement(input.readText()).jsonArray
        require(cases.size in 1..20)
        val reports = mutableListOf<JsonObject>()
        val output = File(application.filesDir, "discovery-audit-results.json")
        for (value in cases) {
            val case = value.jsonObject
            val raw = case.getValue("source").jsonObject
            val name = raw.getValue("bookSourceName").jsonPrimitive.content
            suspend fun measure(stage: String, block: suspend () -> JsonObject) {
                val started = android.os.SystemClock.elapsedRealtime()
                android.util.Log.i("DiscoveryAudit", "start $name $stage")
                val result = try { withTimeout(45_000) { block() } }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    buildJsonObject {
                        put("error", (error as? SourceContentException)?.code?.name ?: error.javaClass.simpleName)
                        (error as? SourceContentException)?.let {
                            put("field", it.field)
                            it.denial?.let { denial -> put("deniedOrigin", denial.origin) }
                        }
                    }
                }
                val report = JsonObject(result + mapOf("source" to JsonPrimitive(name), "stage" to JsonPrimitive(stage),
                    "elapsedMs" to JsonPrimitive(android.os.SystemClock.elapsedRealtime() - started)))
                reports += report
                output.writeText(JsonArray(reports).toString())
                instrumentation.sendStatus(0, Bundle().apply { putString("discoveryAudit", report.toString()) })
            }
            var identity: io.nightfish.lightnovelreader.api.identifier.Identifier? = null
            measure("install") {
                val preview = sources.importer.preview(raw.toString())
                check(preview.issues.isEmpty()) { "Import rejected" }
                val candidate = preview.candidates.single()
                val reference = candidate.existing ?: sources.importer.commit(preview,
                    listOf(ImportSelection(candidate.index, ImportDecision.Add))).items.single().reference!!
                val installed = sources.installedSources().singleOrNull { it.definition.reference() == reference }
                val grants = case.getValue("origins").jsonArray.map { NetworkGrant(it.jsonPrimitive.content) }
                identity = if (installed != null) ImportedRuleSources.id(installed.definition).also { id ->
                    val approved = (installed.origins + grants).distinctBy { it.origin }
                    if (approved != installed.origins) entry.revisions().updatePermissions(id, approved)
                } else {
                    sources.activate(reference, grants).also { sources.setPreferences(it, enabled = true, discoveryVisible = true) }
                }
                buildJsonObject { put("status", "ready"); put("id", identity!!.id) }
            }
            val id = identity ?: continue
            if (args.getString("discoveryAction") == "install") continue
            val source = sources.loginTarget(id).rules
            measure("search") {
                val books = source.search(case["keyword"]?.jsonPrimitive?.content ?: "斗罗", 1)
                buildJsonObject { put("books", books.size) }
            }
            val provider = entry.registry().discovery(id).get()
            if (provider == null) {
                measure("catalog") { buildJsonObject { put("error", "Unsupported") } }
                continue
            }
            val discovery = provider.forSession("device-audit-${id.id}")
            var categories = emptyList<indi.dmzz_yyhyy.lightnovelreader.data.web.SourceDiscoveryCategory>()
            measure("catalog") {
                val result = discovery.catalog()
                result.get()?.let { catalog ->
                    categories = catalog.categories
                    buildJsonObject {
                        put("entries", categories.size); put("controls", catalog.filters.size); put("buttons", catalog.buttons.size)
                        put("firstTitles", JsonArray(categories.take(8).map { JsonPrimitive(it.title) }))
                    }
                } ?: buildJsonObject { put("error", result.getError().toString()); put("field", discovery.failureField) }
            }
            measure("feed") {
                val result = discovery.feed()
                result.get()?.let { sections -> buildJsonObject {
                    put("sections", sections.size); put("previewBooks", sections.sumOf { it.books.size })
                    put("previewFailures", JsonArray(sections.mapNotNull { section -> section.previewFailure?.let { failure ->
                        buildJsonObject {
                            put("title", section.title); put("error", failure.error.name); put("field", failure.field)
                            put("deniedOrigin", failure.permission?.origin)
                        }
                    } }))
                } } ?: buildJsonObject { put("error", result.getError().toString()); put("field", discovery.failureField) }
            }
            for (category in categories.filter { it.target.target.isNotBlank() }.take(2)) measure("entry:${category.title}") {
                val page = discovery.open(category.target)
                val result = page.loadMore()
                result.get()?.let { buildJsonObject { put("books", it.books.size); put("hasMore", it.nextCursor != null) } }
                    ?: buildJsonObject {
                        put("error", result.getError().toString()); put("field", page.failureField)
                        put("deniedOrigin", page.permissionFailure?.origin)
                    }
            }
        }
        // This diagnostic records individual failures; a successful runner is not a source pass rate.
        assertTrue(reports.isNotEmpty())
    }
}
