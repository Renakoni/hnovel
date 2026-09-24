package hnovel.network

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = SourceScope("fixture", "source", "legado")
    private val grants = listOf(NetworkGrant("https://example.org"))
    private val token = StorageRequest(StorageArea.Cache, "value:token", "account-a", ttlMillis = 0)
    private val favorites = StorageRequest(StorageArea.Cache, "file:favorites", "[1,2]", ttlMillis = 0)

    @Test fun restartAndRevisionRetainTheAccountButLogoutPurgesOnlyItsScriptValues() {
        SourceBroker(folder.root.toPath()).use { broker ->
            val a = broker.open(scope, grants)
            a.write(token); a.write(favorites)
            a.write(StorageRequest(StorageArea.Config, "theme", "dark"))
            a.write(StorageRequest(StorageArea.BookState, "book-and-progress", "chapter-3"))
            a.setCookie("https://example.org", "sid=account-a")
            for (other in listOf(scope.copy(sourceId = "b"), scope.copy(profile = "other"), scope.copy(namespace = "other"))) {
                val separate = broker.open(other, grants)
                assertEquals(StorageResult.Value(null), separate.read(token))
                separate.write(token.copy(value = "separate"))
            }
            a.close()
            val revision = broker.open(scope, grants)
            assertEquals(StorageResult.Value(token.value), revision.read(token))
            assertEquals(StorageResult.Value(favorites.value), revision.read(favorites))
        }
        SourceBroker(folder.root.toPath()).use { broker ->
            val a = broker.open(scope, grants)
            assertEquals(StorageResult.Value(token.value), a.read(token))
            a.clearAccount()
            val b = broker.open(scope.copy(accountGeneration = 1), grants)
            assertEquals(StorageResult.Value(null), b.read(token))
            assertEquals(StorageResult.Value(null), b.read(favorites))
            assertEquals("", b.cookie("https://example.org"))
            assertEquals(StorageResult.Value("dark"), b.read(StorageRequest(StorageArea.Config, "theme")))
            assertEquals(StorageResult.Value("chapter-3"), b.read(StorageRequest(StorageArea.BookState, "book-and-progress")))
            assertThrows(IllegalStateException::class.java) { a.write(token) }
            assertEquals(StorageResult.Value("separate"), broker.open(scope.copy(sourceId = "b"), grants).read(token))
            // Cleanup removes data, in addition to making it unreachable by the new generation.
            assertEquals(StorageResult.Value(null), SourceStorage(folder.root.toPath(), scope.components(true) + "cache", BrokerLimits()).read("entries"))
        }
        SourceBroker(folder.root.toPath()).use { broker ->
            assertEquals(StorageResult.Value(null), broker.open(scope.copy(accountGeneration = 1), grants).read(token))
        }
    }

    @Test fun legacyUnownedValuesAreInvalidatedWithoutDiscardingConfiguration() {
        val root = folder.root.toPath()
        val legacy = SourceStorage(root, scope.components(false) + "cache", BrokerLimits())
        ValueCache(BrokerLimits(), legacy).write(token)
        ValueCache(BrokerLimits(), legacy).write(favorites)
        SourceStorage(root, scope.components(false) + "config", BrokerLimits()).write("theme", "dark")
        SourceBroker(root).use { broker ->
            val current = broker.open(scope, grants)
            assertEquals(StorageResult.Value(null), current.read(token))
            assertEquals(StorageResult.Value(null), current.read(favorites))
            assertEquals(StorageResult.Value(null), legacy.read("entries"))
            assertEquals(StorageResult.Value("dark"), current.read(StorageRequest(StorageArea.Config, "theme")))
            current.write(token)
            // Rotation also isolates values when a caller cannot complete old-account cleanup.
            val next = broker.open(scope.copy(accountGeneration = 1), grants)
            assertEquals(StorageResult.Value(null), next.read(token))
            assertThrows(IllegalStateException::class.java) { current.write(token) }
        }
    }
}
