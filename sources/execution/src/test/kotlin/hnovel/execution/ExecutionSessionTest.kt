package hnovel.execution

import hnovel.network.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExecutionSessionTest {
    @get:Rule val root = TemporaryFolder()

    @Test fun ticketsCannotBeReboundAndClosingASessionPermanentlyRevokesThem() {
        val authority = ExecutionAuthority()
        val scope = SourceScope("fixture", "a", "legado")
        SourceBroker(root.newFolder().toPath()).use { first ->
            SourceBroker(root.newFolder().toPath()).use { second ->
                val a = first.open(scope, emptyList())
                val b = second.open(scope, emptyList())
                val ticket = authority.issue("a", "legado", "1", "fixture")
                SourceExecutionBroker(ticket, authority, a, ExecutionLimits()).close()
                assertTrue(authority.accepts(ticket))
                assertThrows(IllegalStateException::class.java) { SourceExecutionBroker(ticket, authority, b, ExecutionLimits()) }
                a.close()
                assertFalse(authority.accepts(ticket))
                assertThrows(IllegalStateException::class.java) { SourceExecutionBroker(ticket, authority, b, ExecutionLimits()) }
                val fresh = authority.issue("a", "legado", "1", "fixture")
                SourceExecutionBroker(fresh, authority, b, ExecutionLimits()).close()
                assertTrue(authority.accepts(fresh))
                second.open(scope.copy(accountGeneration = 1), emptyList())
                assertFalse(authority.accepts(fresh))
            }
        }
    }
}
