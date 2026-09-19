package hnovel.imports

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConcurrentRateImportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun nullableStringRatesAndLegacyNumbersSurvivePreviewAndStorage() {
        for (profile in listOf(LEGADO_PROFILE, EXTENSION_PROFILE)) {
            val root = temp.newFolder().toPath()
            val importer = SourceDefinitionImporter(SourceDefinitionStore(root))
            val values = listOf(JsonNull, JsonPrimitive(""), JsonPrimitive("1000"), JsonPrimitive("30/5000"),
                JsonPrimitive("20/60000"), JsonPrimitive(0), JsonPrimitive(1000))
            for ((index, rate) in values.withIndex()) {
                val raw = buildJsonObject {
                    put("bookSourceUrl", "https://novel.invalid/#$index")
                    put("concurrentRate", rate)
                }
                val preview = importer.preview(raw.toString(), profile)
                assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                val candidate = preview.candidates.single()
                assertEquals(rate, Json.parseToJsonElement(candidate.rawJson).jsonObject["concurrentRate"])
                assertTrue(candidate.notices.any { it.code == "ExecutionCompatibilityPending" })
                assertFalse(candidate.notices.any { it.code == "UnclassifiedField" && it.field == "concurrentRate" })
                assertEquals(ImportOutcome.Added, importer.commit(preview,
                    listOf(ImportSelection(candidate.index, ImportDecision.Add))).items.single().outcome)
            }
            assertEquals(values, SourceDefinitionStore(root).list().map { Json.parseToJsonElement(it.rawJson).jsonObject["concurrentRate"] })
        }
    }

    @Test fun invalidRateTypesDoNotRelaxOtherFieldValidation() {
        val importer = SourceDefinitionImporter(SourceDefinitionStore(temp.newFolder().toPath()))
        for (value in listOf("true", "[]", "{}", "1.5")) {
            val preview = importer.preview("""{"bookSourceUrl":"https://novel.invalid/","concurrentRate":$value}""")
            assertTrue(preview.candidates.isEmpty())
            assertEquals(ImportIssue(0, ImportCode.InvalidField, "concurrentRate"), preview.issues.single())
        }
        val preview = importer.preview("""{"bookSourceUrl":"https://novel.invalid/","concurrentRate":"30/5000","weight":"bad"}""")
        assertEquals(ImportIssue(0, ImportCode.InvalidField, "weight"), preview.issues.single())
    }
}
