package indi.dmzz_yyhyy.lightnovelreader.data.download

import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressLifecycleTest {
    @Test fun shutdownFinishesPendingDatabaseReadBeforeDatabaseCanClose() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val dao = mockk<UserDataDao>()
        coEvery { dao.get(any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() } finally { finished.complete(Unit) }
        }
        val repository = DownloadProgressRepository(dao, mockk(), mockk(relaxed = true))
        try {
            withTimeout(5000) { started.await() }
            withTimeout(5000) { repository.close() }
            assertTrue(finished.isCompleted)
            assertTrue(repository.downloadItemIdList.isEmpty())
        } finally { repository.close() }
    }
}
