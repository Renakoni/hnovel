package hnovel.network

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginPermissionTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = SourceScope("fixture", "A", "legado")

    @Test fun refusalsAreRedactedBoundedAndNeverBecomeGrants() = runBlocking {
        SourceBroker(folder.root.toPath()).use { broker ->
            val a = broker.open(owner, emptyList())
            val b = broker.open(owner.copy(sourceId = "B"), emptyList())
            val request = BrokerRequest("cover", "https://cdn.invalid:8443/book/private?token=secret#fragment", kind = ResourceKind.Image)
            val failure = a.execute(request) as BrokerResult.Failure
            val detail = OriginDenial("https://cdn.invalid:8443", ResourceKind.Image)
            assertEquals(detail, failure.denial)
            assertEquals(listOf(detail), a.deniedOrigins)
            assertEquals(failure, a.execute(request))
            assertEquals(1, a.deniedOrigins.size)
            assertTrue(b.deniedOrigins.isEmpty())
            val wire = Json.encodeToString(BrokerResult.serializer(), failure)
            assertFalse(wire.contains("secret")); assertFalse(wire.contains("private")); assertFalse(wire.contains("fragment"))
            assertEquals(failure, Json.decodeFromString(BrokerResult.serializer(), wire))
            repeat(64) { a.execute(request.copy(url = "https://cdn-$it.invalid/path")) }
            assertEquals(32, a.deniedOrigins.size)
            val replacement = broker.open(owner.copy(accountGeneration = 1), emptyList())
            assertTrue(a.deniedOrigins.isEmpty()); assertTrue(replacement.deniedOrigins.isEmpty())
            assertEquals(FailureCode.OriginDenied, (replacement.execute(request) as BrokerResult.Failure).code)
        }
    }

    @Test fun addressRefusalsCredentialsAndCancelledRequestsDoNotSuggestPermissionChanges() = runBlocking {
        SourceBroker(folder.root.toPath()).use { broker ->
            val session = broker.open(owner, listOf(NetworkGrant("http://127.0.0.1/")))
            assertEquals(FailureCode.AddressDenied, (session.execute(BrokerRequest("private", "http://127.0.0.1/")) as BrokerResult.Failure).code)
            assertEquals(FailureCode.InvalidRequest, (session.execute(BrokerRequest("credentials", "https://user:secret@source.invalid/")) as BrokerResult.Failure).code)
            assertTrue(session.deniedOrigins.isEmpty())
            val error = runCatching {
                session.execute(BrokerRequest("cancelled", "https://unapproved.invalid/"), RequestCommitGuard { throw CancellationException() })
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertTrue(session.deniedOrigins.isEmpty())
        }
    }

    @Test fun malformedWireDetailsCannotDisplayAFullUrlAndIpv6OriginsRemainValid() {
        assertThrows(IllegalArgumentException::class.java) { OriginDenial("https://cdn.invalid/private?token=secret", ResourceKind.Image) }
        assertThrows(IllegalArgumentException::class.java) { OriginDenial("https://user:secret@cdn.invalid:443", ResourceKind.Image) }
        assertEquals("https://[::1]:443", sourceOrigin("https://[::1]/private"))
        assertEquals(BrokerResult.Failure(RequestStage.Connect, FailureCode.Network),
            Json.decodeFromString(BrokerResult.serializer(), """{"type":"hnovel.network.BrokerResult.Failure","stage":"Connect","code":"Network"}"""))
    }
}
