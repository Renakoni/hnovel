package indi.renakoni.nextvol.data.web

import android.app.Application
import hnovel.imports.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceCatalogTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalog = SourceCatalog(RuntimeEnvironment.getApplication())

    @Test fun all89CatalogEntriesUseTheirBundledDefinitionsAndPassTheProductionImporter() {
        assertEquals(listOf(19, 19, 14, 16, 12, 9), SourceCategory.entries.map { category -> catalog.entries.count { it.category == category } })
        assertEquals(89, catalog.entries.map { it.key }.toSet().size)
        val store = SourceDefinitionStore(folder.newFolder().toPath())
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview(catalog.definitions(catalog.entries.map { it.key }.toSet()), AUTO_PROFILE)
        assertEquals(emptyList<ImportIssue>(), preview.issues)
        assertEquals(catalog.entries.map { it.key }, preview.candidates.map { it.importKey })
        assertTrue(store.list().isEmpty())
        for (category in SourceCategory.entries) {
            val original = RuntimeEnvironment.getApplication().assets.open("source-catalog/${category.name}.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonArray
            }
            val entries = catalog.entries.filter { it.category == category }
            assertEquals(original, Json.parseToJsonElement(catalog.definitions(entries.map { it.key }.toSet())))
        }
    }

    @Test fun bundledDefinitionsExcludeFixedAccountAndTrackingHeaders() {
        val entries = listOf(
            catalog.entries.single { it.key.startsWith("https://api.uaa.com") } to setOf("cookie", "token"),
            catalog.entries.single { it.key == "http://api.doufu.me/" } to setOf("cookie"),
        )
        entries.forEach { (entry, removed) ->
            val definition = Json.parseToJsonElement(catalog.definitions(setOf(entry.key))).jsonArray.single().jsonObject
            val headers = Json.parseToJsonElement(definition.getValue("header").jsonPrimitive.content).jsonObject
            assertTrue(headers.keys.none { it.lowercase() in removed })
            if (entry.key.startsWith("https://api.uaa.com")) {
                assertTrue(headers.keys.any { it.equals("User-Agent", ignoreCase = true) })
            }
        }
    }

    @Test fun crossCategorySelectionPreviewsOnlyChosenSourcesAndNamesNeverDetermineClassification() {
        val chosen = listOf(catalog.entries.first { it.category == SourceCategory.Female }, catalog.entries.last())
        val importer = SourceDefinitionImporter(SourceDefinitionStore(folder.newFolder().toPath()))
        val preview = importer.preview(catalog.definitions(chosen.map { it.key }.toSet()), AUTO_PROFILE)
        assertEquals(chosen.map { it.key }, preview.candidates.map { it.importKey })
        val results = importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
        assertTrue(results.items.all { it.error == null })
        val sameName = SourceDefinition("unrelated", "legado", LEGADO_PROFILE, "https://unrelated.invalid/", chosen.first().name,
            true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        assertNull(catalog.entry(sameName))
        assertEquals(chosen.first().category, catalog.entry(sameName.copy(importKey = chosen.first().key, displayName = "Renamed"))?.category)
    }
}
