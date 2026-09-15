package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import hnovel.content.*
import hnovel.network.BrowserChallengeKind
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceVerificationCoordinatorTest {
    private val owner = VerificationOwner(Identifier("rules", "fixture"), "revision", 0)
    private val listings = MutableStateFlow(listOf(SourceListing(SourceMetadata(
        WebDataSourceItem(owner.source, "Fixture", ""), emptySet(), revision = owner.revision), SourceStatus.Ready)))
    private val coordinator = SourceVerificationCoordinator(mockk<WebSourceRegistry> {
        every { sources } returns listings
    })
    private val verification = mockk<SourceVerification> {
        every { kind } returns BrowserChallengeKind.Cloudflare
        coEvery { complete() } returns Unit
    }
    private val failure = SourceContentException(ContentError.BrowserRequired, "searchUrl", verification = verification)

    @Test fun foregroundAutomaticallyOpensAndRetriesOnce() = runTest {
        var calls = 0
        val completed = CompletableDeferred<Unit>()
        coEvery { verification.complete() } coAnswers { completed.await() }
        val request = async(ForegroundSourceRequest()) { coordinator.execute(owner, "Fixture") {
            if (++calls == 1) throw failure
            "accepted"
        } }
        runCurrent()
        assertEquals(1, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.single().opening)
        completed.complete(Unit)
        assertEquals("accepted", request.await())
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun cancelledQueuedRequestNeverOpensAndDoesNotCancelTheActiveBrowser() = runTest {
        val completed = CompletableDeferred<Unit>()
        coEvery { verification.complete() } coAnswers { completed.await() }
        val first = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "First") { throw failure } } }
        val second = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Second") { throw failure } } }
        runCurrent()
        assertEquals(2, coordinator.prompts.value.size)
        second.cancelAndJoin()
        runCurrent()
        assertFalse(first.isCompleted)
        assertEquals("First", coordinator.prompts.value.single().name)
        coVerify(exactly = 1) { verification.complete() }
        completed.complete(Unit)
        assertSame(failure, first.await().exceptionOrNull())
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun backgroundReturnsImmediatelyAndOnlyUserActionOpensTheBrowser() = runTest {
        assertSame(failure, runCatching { coordinator.execute(owner, "Fixture") { throw failure } }.exceptionOrNull())
        val prompt = coordinator.prompts.value.single()
        assertFalse(prompt.foreground)
        coVerify(exactly = 0) { verification.complete() }
        coordinator.verifyBackground(prompt.id)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun accountRetirementPreventsRetryAfterTheBrowserReturns() = runTest {
        val monitor = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.observeRetirement() }
        val completed = CompletableDeferred<Unit>()
        coEvery { verification.complete() } coAnswers { completed.await() }
        var calls = 0
        val request = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { calls++; throw failure } } }
        runCurrent()
        listings.value = listings.value.map { it.copy(metadata = it.metadata.copy(accountGeneration = 1)) }
        runCurrent()
        completed.complete(Unit)
        assertEquals(ContentError.Unavailable, (request.await().exceptionOrNull() as SourceContentException).code)
        assertEquals(1, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
        monitor.cancel()
    }

    @Test fun challengeAfterVerificationDoesNotOpenAnAutomaticLoop() = runTest {
        var calls = 0
        val request = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { calls++; throw failure } } }
        runCurrent()
        assertSame(failure, request.await().exceptionOrNull())
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun leavingTheUiCancelsOnlyItsWaitingVerification() = runTest {
        val ui = ForegroundSourceRequest()
        coEvery { verification.complete() } coAnswers { awaitCancellation() }
        val request = async(ui) { coordinator.execute(owner, "Fixture") { throw failure } }
        runCurrent()
        ui.setActive(false)
        request.join()
        assertTrue(request.isCancelled)
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 1) { verification.complete() }
    }

    @Test fun browserCoverRetainsTheRequestButNavigationAwayCancelsIt() = runTest {
        val ui = ForegroundSourceRequest()
        coEvery { verification.complete() } coAnswers { awaitCancellation() }
        val request = async(ui) { coordinator.execute(owner, "Fixture") { throw failure } }
        runCurrent()
        assertTrue(ui.verifying)
        ui.setActive(false, retainBrowser = true)
        runCurrent()
        assertFalse(request.isCompleted)
        ui.setActive(false)
        request.join()
        assertTrue(request.isCancelled)
        assertFalse(ui.verifying)
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun preloadExplicitlyDropsInheritedInteractionAuthority() = runTest {
        withContext(ForegroundSourceRequest(allowsInteraction = false)) {
            assertSame(failure, runCatching { coordinator.execute(owner, "Fixture") { throw failure } }.exceptionOrNull())
        }
        assertFalse(coordinator.prompts.value.single().foreground)
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun inactiveUiCannotOpenVerification() = runTest {
        val ui = ForegroundSourceRequest().apply { setActive(false) }
        val request = async(ui) { coordinator.execute(owner, "Fixture") { throw failure } }
        request.join()
        assertTrue(request.isCancelled)
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun successfulPublicRequestNeverOpensVerification() = runTest {
        assertEquals("public catalog", withContext(ForegroundSourceRequest()) {
            coordinator.execute(owner, "Fixture") { "public catalog" }
        })
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun replacedRevisionAndDisabledSourcesCannotOpenBackgroundTickets() = runTest {
        val original = listings.value
        for (retired in listOf(original.map { it.copy(metadata = it.metadata.copy(revision = "replaced")) },
            original.map { it.copy(status = SourceStatus.Failed) }, emptyList())) {
            listings.value = original
            assertSame(failure, runCatching { coordinator.execute(owner, "Fixture") { throw failure } }.exceptionOrNull())
            val ticket = coordinator.prompts.value.single()
            listings.value = retired
            val rejected = runCatching { coordinator.verifyBackground(ticket.id) }.exceptionOrNull() as SourceContentException
            assertEquals(ContentError.Unavailable, rejected.code)
            assertTrue(coordinator.prompts.value.isEmpty())
        }
        coVerify(exactly = 0) { verification.complete() }
    }
}
