package hnovel.compatibility

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CorpusIntegrityTest {
    @Test
    fun vendoredSelectorsAreTheUnmodifiedPinnedSnapshot() {
        val root = File(System.getProperty("compatibility.projectDir"))
        val provenance = JsonParser.parseString(File(root, "reference/provenance.json").readText()).asJsonObject
        assertEquals("da17bb2bed44f30b12a524c2457e32a20b16fa41", provenance.string("revision"))
        for (entry in provenance.getAsJsonArray("files")) {
            val file = entry.asJsonObject
            val bytes = File(root, file.string("path")).readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(file.string("path"), file.string("sha256"), digest)
        }
    }

    @Test
    fun mismatchAndMissingExecutionFailInsteadOfBecomingCompatibilitySuccess() {
        val fixture = FixtureCorpus.cases().first { it.has("expected") }.deepCopy()
        val wrong = JsonParser.parseString("[\"deliberately wrong\"]")
        assertThrows(AssertionError::class.java) {
            FixtureCorpus.assertOutput(fixture.string("id"), wrong, ReferenceRunner.evaluate(fixture))
        }
        fixture.addProperty("operation", "not-implemented")
        assertThrows(IllegalStateException::class.java) { ReferenceRunner.evaluate(fixture) }
        assertThrows(IllegalArgumentException::class.java) { FixtureCorpus.text("missing-fixture.json") }
    }

    @Test
    fun coverageHasOwnersAndSeparatesReferenceEvidenceFromProductImplementation() {
        val cases = FixtureCorpus.cases()
        val caseIds = cases.map { it.string("id") }.toSet()
        assertEquals(cases.size, caseIds.size)
        val manifest = FixtureCorpus.json("coverage.json")
        val features = manifest.getAsJsonArray("features")
        val featureIds = features.map { it.asJsonObject.string("id") }
        assertEquals(featureIds.size, featureIds.toSet().size)
        val referencedCases = mutableSetOf<String>()
        val testIds = mutableSetOf<String>()
        for (entry in features) {
            val feature = entry.asJsonObject
            assertTrue(feature.string("owner").matches(Regex("https://github.com/Renakoni/hnovel/issues/\\d+")))
            assertTrue(feature.string("requirement").isNotBlank())
            assertTrue(feature.string("evidence").isNotBlank())
            assertTrue(testIds.add(feature.string("testId")))
            // No product engine exists yet. Change this gate together with the first
            // real differential adapter, never because an oracle test is green.
            assertEquals("planned", feature.string("implementation"))
            val fixtures = feature.getAsJsonArray("fixtures").map { it.asString }
            if (feature.string("verification") == "reference-fixture") assertTrue(fixtures.isNotEmpty())
            for (fixture in fixtures) {
                assertTrue("Unknown fixture $fixture", fixture in caseIds)
                referencedCases.add(fixture)
            }
        }
        assertEquals(caseIds, referencedCases)
        for (case in cases) {
            assertTrue(case.string("evidence").isNotBlank())
            assertTrue(case.string("oracle") in setOf("pinned-selector", "rhino-contract", "mixed-contract"))
            assertTrue(case.has("expected") xor case.has("expectedError"))
        }
        val report = File(System.getProperty("compatibility.reportDir"), "coverage.json")
        report.parentFile.mkdirs()
        report.writeText(FixtureCorpus.gson.toJson(manifest) + "\n")
    }

    @Test
    fun syntheticSourcesCoverTheRequiredFamiliesWithoutPluginPackages() {
        val sources = FixtureCorpus.json("sources.json").getAsJsonArray("sources")
        assertEquals(6, sources.size())
        val keys = mutableSetOf<String>()
        for (item in sources) {
            val source = item.asJsonObject
            assertEquals(0, source.get("bookSourceType").asInt)
            assertTrue(source.string("bookSourceUrl").startsWith("https://reader.invalid/"))
            assertTrue(keys.add(source.string("bookSourceUrl")))
            for (stage in listOf("ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent")) {
                assertTrue(stage, source.getAsJsonObject(stage).size() > 0)
            }
        }
        val inventory = FixtureCorpus.json("advanced-inventory.json")
        val mapped = inventory.getAsJsonArray("members").map { it.asJsonObject.string("member") }
        assertEquals(mapped.size, mapped.toSet().size)
        val featureIds = FixtureCorpus.json("coverage.json").getAsJsonArray("features")
            .map { it.asJsonObject.string("id") }.toSet()
        for (entry in inventory.getAsJsonArray("members")) {
            assertTrue(entry.asJsonObject.string("feature") in featureIds)
        }
        assertTrue(mapped.containsAll(listOf("infoMap.save", "infoMap.set", "java.refreshExplore")))
    }
}
