package indi.dmzz_yyhyy.lightnovelreader.data.web.proxy

import android.app.Application
import com.github.michaelbull.result.Ok
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyCoalescingWebBookDataSourceTest {
    @Test
    fun cancellingOneWaiterDoesNotCancelTheSharedRequestOrRetainItsCompletedResult() = runTest {
        val remote = mockk<ProxyWebBookDataSource>()
        val release = CompletableDeferred<Unit>()
        val volumes = BookVolumes("same", emptyList())
        coEvery { remote.getBookVolumes("same", any()) } coAnswers {
            release.await(); Ok(volumes)
        }
        val proxy = ProxyCoalescingWebBookDataSource(remote, StandardTestDispatcher(testScheduler))
        try {
            val first = async { proxy.getBookVolumes("same", WebDataSourcePriority.Default) }
            val second = async { proxy.getBookVolumes("same", WebDataSourcePriority.Default) }
            runCurrent()
            first.cancelAndJoin()
            coVerify(exactly = 1) { remote.getBookVolumes("same", WebDataSourcePriority.Default) }
            release.complete(Unit)
            assertEquals(Ok(volumes), second.await())
            assertEquals(Ok(volumes), proxy.getBookVolumes("same", WebDataSourcePriority.Default))
            coVerify(exactly = 2) { remote.getBookVolumes("same", WebDataSourcePriority.Default) }
        } finally { release.complete(Unit); proxy.closeAndJoin() }
    }

    @Test
    fun shutdownWaitsForRemoteCancellationCleanupBeforeResourcesCanBeClosed() = runTest {
        val remote = mockk<ProxyWebBookDataSource>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        coEvery { remote.getBookVolumes("same", any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }
        val proxy = ProxyCoalescingWebBookDataSource(remote, StandardTestDispatcher(testScheduler))
        try {
            val request = async { proxy.getBookVolumes("same", WebDataSourcePriority.Default) }
            runCurrent()
            val shutdown = async { proxy.closeAndJoin() }
            runCurrent()
            assertTrue(cleanupStarted.isCompleted)
            assertFalse(shutdown.isCompleted)
            releaseCleanup.complete(Unit)
            shutdown.await()
            request.join()
            assertTrue(request.isCancelled)
        } finally { releaseCleanup.complete(Unit); proxy.closeAndJoin() }
    }

    @Test
    fun highPriorityDoesNotInheritALowPriorityRequestsScheduling() = runTest {
        val remote = mockk<ProxyWebBookDataSource>()
        val release = CompletableDeferred<Unit>()
        coEvery { remote.getBookVolumes("same", any()) } coAnswers {
            release.await(); Ok(BookVolumes("same", emptyList()))
        }
        val proxy = ProxyCoalescingWebBookDataSource(remote, StandardTestDispatcher(testScheduler))
        try {
            val low = async { proxy.getBookVolumes("same", WebDataSourcePriority.Low) }
            val high = async { proxy.getBookVolumes("same", WebDataSourcePriority.High) }
            runCurrent()
            coVerify(exactly = 1) { remote.getBookVolumes("same", WebDataSourcePriority.Low) }
            coVerify(exactly = 1) { remote.getBookVolumes("same", WebDataSourcePriority.High) }
            release.complete(Unit)
            low.await()
            high.await()
        } finally { release.complete(Unit); proxy.close() }
    }

    @Test
    fun identicalRequestsCoalesceButInformationAndDirectoryNeverShareAnInFlightValue() = runTest {
        val remote = mockk<ProxyWebBookDataSource>()
        val release = CompletableDeferred<Unit>()
        val information = mockk<BookInformation>()
        val volumes = BookVolumes("same", emptyList())
        coEvery { remote.getBookInformation("same", WebDataSourcePriority.Default) } coAnswers {
            release.await(); Ok(information)
        }
        coEvery { remote.getBookVolumes("same", WebDataSourcePriority.Default) } coAnswers {
            release.await(); Ok(volumes)
        }
        val proxy = ProxyCoalescingWebBookDataSource(remote, StandardTestDispatcher(testScheduler))
        try {
            val a = async { proxy.getBookInformation("same", WebDataSourcePriority.Default) }
            val b = async { proxy.getBookInformation("same", WebDataSourcePriority.Default) }
            val c = async { proxy.getBookVolumes("same", WebDataSourcePriority.Default) }
            runCurrent()
            coVerify(exactly = 1) { remote.getBookInformation("same", WebDataSourcePriority.Default) }
            coVerify(exactly = 1) { remote.getBookVolumes("same", WebDataSourcePriority.Default) }
            release.complete(Unit)
            assertEquals(Ok(information), a.await())
            assertEquals(Ok(information), b.await())
            assertEquals(Ok(volumes), c.await())
        } finally {
            release.complete(Unit)
            proxy.close()
        }
    }
}
