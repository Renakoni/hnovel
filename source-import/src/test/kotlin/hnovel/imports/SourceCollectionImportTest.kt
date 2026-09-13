package hnovel.imports

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceCollectionImportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun bookSourceLinksResolveWithoutAcceptingOtherLegadoActions() {
        val url = "https://reader.invalid/collection.json?q=a%2Bb"
        assertEquals(url, sourceImportUrl(url))
        assertEquals(url, sourceImportUrl("legado://import/bookSource?src=" + java.net.URLEncoder.encode(url, "UTF-8")))
        assertEquals("https://reader.invalid/a.json", sourceImportUrl("legado://import/bookSource?src=https://reader.invalid/a.json"))
        for (link in listOf("legado://import/rssSource?src=https://reader.invalid/", "legado://import/bookSource?src=file:///private",
            "legado://import/bookSource?src=https://reader.invalid/&src=https://other.invalid/")) assertNull(sourceImportUrl(link))
    }

    @Test fun collectionKeepsCompleteDefinitionsAndSelectsTheNewestDuplicate() {
        val root = temp.newFolder().toPath()
        val store = SourceDefinitionStore(root)
        val importer = SourceDefinitionImporter(store)
        val rows = (0 until 1400).map { """{"bookSourceUrl":"https://reader.invalid/$it","bookSourceName":"Novel $it","ruleToc":{"chapterList":"all-chapters"}}""" }
        val input = "[" + (rows + listOf(
            """{"bookSourceUrl":"https://reader.invalid/0","lastUpdateTime":2,"loginUi":"@js:[]","ruleToc":{"chapterList":"new-all-chapters"}}""",
            """{"bookSourceUrl":"https://audio.invalid/","bookSourceType":1}""")).joinToString(",") + "]"
        val preview = importer.preview(input, AUTO_PROFILE)
        assertEquals(1401, preview.candidates.size)
        assertEquals(ImportCode.UnsupportedType, preview.issues.single().code)
        val selected = preview.candidates.deduplicated()
        assertEquals(1400, selected.size)
        assertEquals(1400, selected.single { it.importKey.endsWith("/0") }.index)
        assertEquals(EXTENSION_PROFILE, selected.single { it.importKey.endsWith("/0") }.profile)
        val result = importer.commit(preview, selected.map { ImportSelection(it.index, ImportDecision.Add) })
        assertNull(result.error)
        assertTrue(result.items.all { it.outcome == ImportOutcome.Added })
        assertEquals(1400, SourceDefinitionStore(root).list().size)
        assertTrue(importer.preview(input, AUTO_PROFILE).candidates.all { it.existing != null })
    }
}
