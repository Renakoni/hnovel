package hnovel.execution

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceIdentifierTest {
    @get:Rule val root = TemporaryFolder()

    @Test fun identifiersPersistAcrossAccountsButStayInsideSourceProfileAndInstallation() = runBlocking {
        val authority = ExecutionAuthority()
        val path = root.newFolder().toPath()
        suspend fun read(backend: SourceBroker, source: String = "a", profile: String = "legado", account: Long = 0): String {
            val id = authority.issue(source, profile, "1", "fixture", account)
            val session = backend.open(SourceScope("fixture", source, profile, account), emptyList())
            return SourceExecutionBroker(id, authority, session, ExecutionLimits()).use {
                it.call("java.androidId", emptyList()).jsonPrimitive.content
            }
        }
        val first = SourceBroker(path).use { backend ->
            val value = read(backend)
            assertTrue(value.matches(Regex("[0-9a-f]{16}")))
            assertEquals(value, read(backend))
            assertEquals(value, read(backend, account = 1))
            assertNotEquals(value, read(backend, source = "b"))
            assertNotEquals(value, read(backend, profile = "different"))
            value
        }
        SourceBroker(path).use { assertEquals(first, read(it, account = 2)) }
        SourceBroker(root.newFolder().toPath()).use { assertNotEquals(first, read(it)) }
    }
}
