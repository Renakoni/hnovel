package hnovel.imports
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class SourceDefinitionImporterTest {
 @Test fun objectArrayUnknownFieldsAndFragmentIdentity() { val i=SourceDefinitionImporter(); val p=i.preview("""[{"bookSourceUrl":"https://x/a#one","bookSourceName":"A","future":1},{"bookSourceUrl":"https://x/a#two","future":2}]"""); assertEquals(2,p.definitions.size); assertNotEquals(p.definitions[0].sourceId,p.definitions[1].sourceId); assertTrue(p.definitions[1].raw.containsKey("future")) }
 @Test fun invalidDoesNotReplaceExisting() { val d=Files.createTempDirectory("imports"); val i=SourceDefinitionImporter(); i.import("""{"bookSourceUrl":"https://x","bookSourceName":"ok"}""",d); val before=Files.list(d).use{it.count()}; assertTrue(i.preview("bad").issues.isNotEmpty()); assertEquals(before,Files.list(d).use{it.count()}) }
 @Test fun stableReimportAndAtomicRoundTrip() { val d=Files.createTempDirectory("imports"); val i=SourceDefinitionImporter(); val a=i.import("""{"bookSourceUrl":"https://x","bookSourceName":"a"}""",d).definitions.single(); val b=i.import("""{"bookSourceUrl":"https://x","bookSourceName":"b","x":true}""",d).definitions.single(); assertEquals(a.sourceId,b.sourceId); assertEquals(1,Files.list(d).use{it.count()}) }
}
