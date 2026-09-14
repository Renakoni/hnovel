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

    @Test fun foregroundWaitsForExplicitActionAndRetriesOnce() = runTest {
        var calls = 0
        val request = async(ForegroundSourceRequest()) { coordinator.execute(owner, "Fixture") {
            if (++calls == 1) throw failure
            "accepted"
        } }
        runCurrent()
        assertEquals(1, calls)
        coVerify(exactly = 0) { verification.complete() }
        val prompt = coordinator.prompts.value.single()
        coordinator.approve(prompt.id)
        assertEquals("accepted", request.await())
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun cancellationRemovesItsPromptAndCannotResumeAnotherRequest() = runTest {
        val first = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "First") { throw failure } } }
        val second = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Second") { throw failure } } }
        runCurrent()
        val stale = coordinator.prompts.value.first().id
        first.cancelAndJoin()
        coordinator.approve(stale)
        runCurrent()
        assertFalse(second.isCompleted)
        coVerify(exactly = 0) { verification.complete() }
        coordinator.dismiss(coordinator.prompts.value.single().id)
        assertSame(failure, second.await().exceptionOrNull())
    }

    @Test fun backgroundReturnsImmediatelyAndOnlyUserActionOpensTheBrowser() = runTest {
        assertSame(failure, runCatching { coordinator.execute(owner, "Fixture") { throw failure } }.exceptionOrNull())
        val prompt = coordinator.prompts.value.single()
        assertFalse(prompt.foreground)
        coordinator.approve(prompt.id)
        coVerify(exactly = 0) { verification.complete() }
        coordinator.verifyBackground(prompt.id)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun accountRetirementCancelsThePendingDecision() = runTest {
        val monitor = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.observeRetirement() }
        val request = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { throw failure } } }
        runCurrent()
        val prompt = coordinator.prompts.value.single()
        listings.value = listings.value.map { it.copy(metadata = it.metadata.copy(accountGeneration = 1)) }
        runCurrent()
        coordinator.approve(prompt.id)
        assertSame(failure, request.await().exceptionOrNull())
        coVerify(exactly = 0) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
        monitor.cancel()
    }

    @Test fun challengeAfterVerificationDoesNotOpenAnAutomaticLoop() = runTest {
        var calls = 0
        val request = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { calls++; throw failure } } }
        runCurrent()
        coordinator.approve(coordinator.prompts.value.single().id)
        assertSame(failure, request.await().exceptionOrNull())
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun leavingTheUiCancelsOnlyItsWaitingVerification() = runTest {
        val ui = ForegroundSourceRequest()
        val request = async(ui) { coordinator.execute(owner, "Fixture") { throw failure } }
        runCurrent()
        val prompt = coordinator.prompts.value.single()
        ui.setActive(false)
        request.join()
        coordinator.approve(prompt.id)
        assertTrue(request.isCancelled)
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun browserCoverRetainsTheRequestButNavigationAwayCancelsIt() = runTest {
        val ui = ForegroundSourceRequest()
        coEvery { verification.complete() } coAnswers { awaitCancellation() }
        val request = async(ui) { coordinator.execute(owner, "Fixture") { throw failure } }
        runCurrent()
        coordinator.approve(coordinator.prompts.value.single().id)
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
}
