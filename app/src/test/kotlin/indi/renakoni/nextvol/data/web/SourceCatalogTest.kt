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

    private val assets get() = RuntimeEnvironment.getApplication().assets
    private fun allEntries() = assets.open("source-catalog/catalog.json").bufferedReader().use {
        Json.decodeFromString<List<CatalogSource>>(it.readText())
    }
    private fun raw(entry: CatalogSource) = assets.open("source-catalog/${entry.category.name}.json").bufferedReader().use {
        Json.parseToJsonElement(it.readText()).jsonArray[entry.index].jsonObject
    }

    @Test fun catalogEntriesUseTheirBundledDefinitionsAndPassTheProductionImporter() {
        assertEquals(listOf(7, 3, 6, 14, 9, 6, 29), SourceCategory.entries.map { category -> catalog.entries.count { it.category == category } })
        assertEquals(74, catalog.entries.map { it.key }.toSet().size)
        val store = SourceDefinitionStore(folder.newFolder().toPath())
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview(catalog.definitions(catalog.entries.map { it.key }.toSet()), AUTO_PROFILE)
        assertEquals(emptyList<ImportIssue>(), preview.issues)
        assertEquals(catalog.entries.map { it.key }, preview.candidates.map { it.importKey })
        assertTrue(store.list().isEmpty())
        for (category in SourceCategory.entries) {
            val entries = catalog.entries.filter { it.category == category }
            if (entries.isNotEmpty()) assertEquals(JsonArray(entries.map(::raw)),
                Json.parseToJsonElement(catalog.definitions(entries.map { it.key }.toSet())))
        }
    }

    @Test fun withdrawnSourcesRetainInstalledClassificationButCannotBeAddedFromTheCatalog() {
        val entries = allEntries()
        assertEquals(92, entries.size)
        val importer = SourceDefinitionImporter(SourceDefinitionStore(folder.newFolder().toPath()))
        val preview = importer.preview(JsonArray(entries.map(::raw)).toString(), AUTO_PROFILE)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        for (entry in entries.filterNot { it.available }) {
            assertTrue(runCatching { catalog.definitions(setOf(entry.key)) }.isFailure)
            val definition = SourceDefinition("installed", "legado", LEGADO_PROFILE, entry.key, "Renamed",
                true, true, ImportOrigin(ImportOrigin.Kind.Paste), "custom", 1, "{}")
            assertEquals(entry.category, catalog.entry(definition)?.category)
        }
        assertTrue(entries.filter { it.name in setOf("轻小说机翻", "连城读书", "ESJ Zone", "鲸云轻说", "疯情书库", "八一中文", "全本小说（quanben5）", "趣书网（qubook）", "掌阅") }
            .all { !it.available })
        assertTrue(entries.filter { raw(it)["exploreUrl"]?.jsonPrimitive?.content.isNullOrBlank() }.all { !it.available })
    }

    @Test fun officialPlatformsAreExplicitAndSeparateFromFreeRecommendations() {
        val official = catalog.entries.filter { it.category == SourceCategory.Official }
        assertTrue(official.map { it.name }.containsAll(setOf("起点中文网", "纵横中文网", "磨铁中文", "红袖添香", "潇湘书院",
            "花溪小说", "米国度", "刺猬猫", "次元姬", "晋江文学城", "QQ阅读", "长佩文学", "SF轻小说／菠萝包")))
        val free = catalog.entries.filterNot { it.category == SourceCategory.Official }
        assertTrue(free.map { it.name }.containsAll(setOf("番茄小说", "七猫小说", "爱丽丝书屋", "hlib", "疯读小说",
            "33言情", "言情书吧", "轻小说百科", "全本同人", "国学典籍（新都）", "书海阁", "八叉书库", "涩涩俱乐部",
            "笔趣阁 · biqusa", "笔趣阁 · 365", "经典书库")))
        assertTrue(official.none { site -> free.any { it.key == site.key } })
        val raw = Json.parseToJsonElement(catalog.definitions(free.map { it.key }.toSet())).jsonArray
        assertEquals(free.map { it.key }, raw.map { it.jsonObject.getValue("bookSourceUrl").jsonPrimitive.content })
    }

    @Test fun bundledRepairsMatchOnlyTheirPublishedOriginalDigests() {
        for (entry in allEntries().filter { it.replaces.isNotEmpty() }) {
            val original = SourceDefinition("installed", "legado", LEGADO_PROFILE, entry.key, "Renamed",
                true, true, ImportOrigin(ImportOrigin.Kind.Paste), entry.replaces.single(), 1, "{}")
            assertEquals(JsonArray(listOf(raw(entry))).toString(), catalog.replacement(original))
            assertNull(catalog.replacement(original.copy(contentDigest = "user-edited")))
            assertNull(catalog.replacement(original.copy(importKey = "https://unrelated.invalid/")))
        }
        val ciweimao = raw(allEntries().single { it.key == "https://www.ciweimao.com/" })
        assertTrue(ciweimao.getValue("browserRead").jsonPrimitive.boolean)
        assertTrue(ciweimao.getValue("enabledCookieJar").jsonPrimitive.boolean)
        val liancheng = raw(allEntries().single { it.name == "连城读书" })
        assertEquals("[]", liancheng.getValue("homepageModules").jsonPrimitive.content)
    }

    @Test fun bundledDefinitionsExcludeFixedAccountAndTrackingHeaders() {
        val entries = listOf(
            allEntries().single { it.key.startsWith("https://api.uaa.com") } to setOf("cookie", "token"),
            allEntries().single { it.key == "http://api.doufu.me/" } to setOf("cookie"),
            allEntries().single { it.key == "https://m.shuhaige.net/" } to setOf("cookie", "token"),
        )
        entries.forEach { (entry, removed) ->
            val definition = raw(entry)
            val headers = Json.parseToJsonElement(definition.getValue("header").jsonPrimitive.content).jsonObject
            assertTrue(headers.keys.none { it.lowercase() in removed })
            if (entry.key.startsWith("https://api.uaa.com")) {
                assertTrue(headers.keys.any { it.equals("User-Agent", ignoreCase = true) })
            }
        }
    }

    @Test fun crossCategorySelectionPreviewsOnlyChosenSourcesAndNamesNeverDetermineClassification() {
        val chosen = listOf(catalog.entries.first { it.category == SourceCategory.Anime }, catalog.entries.last())
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
