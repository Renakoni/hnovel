package hnovel.compatibility

import hnovel.imports.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductImportFixtureTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun syntheticSourcesUseProductionImportAndRemainDefinitionsUntilRuntimeIntegration() {
        val input = FixtureCorpus.json("sources.json").getAsJsonArray("sources")
        val store = SourceDefinitionStore(temp.newFolder().toPath())
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview(input.toString())
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertEquals(input.size(), preview.candidates.size)
        assertTrue(store.list().isEmpty())
        val committed = importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
        assertTrue(committed.items.all { it.outcome == ImportOutcome.Added })
        assertEquals(input.size(), store.list().size)
        for ((index, source) in store.list().withIndex()) {
            assertEquals(input[index].asJsonObject.get("bookSourceUrl").asString, source.importKey)
            assertEquals(LEGADO_PROFILE, source.profile)
            assertTrue(preview.candidates[index].notices.any { it.code == "ExecutionCompatibilityPending" })
        }
    }
}
