package hnovel.network

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ValueCacheTest {
    @get:Rule val directory = TemporaryFolder()
    private val limits = BrokerLimits()
    private var now = 1_000L

    private fun reopen() = ValueCache(limits,
        SourceStorage(directory.root.toPath(), listOf("test-cache"), limits), nowMillis = { now })

    @Test fun persistedValueIsReadableUntilItsDeadlineAndExpiresExactlyAtIt() {
        val item = StorageRequest(StorageArea.Cache, "key", "value", ttlMillis = 20)
        assertEquals(StorageResult.Value("value"), reopen().write(item))
        assertEquals(StorageResult.Value("value"), reopen().read("key"))
        now += 19
        assertEquals(StorageResult.Value("value"), reopen().read("key"))
        now++
        assertEquals(StorageResult.Value(null), reopen().read("key"))
    }

    @Test fun zeroTtlHasNoDeadlineAndReplacementGetsANewDeadline() {
        val item = StorageRequest(StorageArea.Cache, "key", "value", ttlMillis = 0)
        assertEquals(StorageResult.Value("value"), reopen().write(item))
        now += 1_000_000
        assertEquals(StorageResult.Value("value"), reopen().read("key"))
        assertEquals(StorageResult.Value("new"), reopen().write(item.copy(value = "new", ttlMillis = 20)))
        now += 19
        assertEquals(StorageResult.Value("new"), reopen().read("key"))
        now++
        assertEquals(StorageResult.Value(null), reopen().read("key"))
    }
}
