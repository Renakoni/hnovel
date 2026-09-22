package indi.renakoni.nextvol.data.web.rules

import hnovel.content.*
import hnovel.network.BrowserChallengeKind
import indi.renakoni.nextvol.data.web.*
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
        every { certificate } returns null
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

    @Test fun foregroundCertificateWaitsForExplicitConfirmationBeforeTrustingAndRetrying() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        var calls = 0
        val request = async(ForegroundSourceRequest()) { coordinator.execute(owner, "Fixture") {
            if (++calls == 1) throw failure
            "chapter"
        } }
        runCurrent()
        val prompt = coordinator.prompts.value.single()
        assertTrue(prompt.confirmingCertificate)
        assertFalse(prompt.opening)
        assertEquals(1, calls)
        coVerify(exactly = 0) { verification.complete() }
        coordinator.approveCertificate(prompt.id)
        assertEquals("chapter", request.await())
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun dismissingCertificateDoesNotTrustOrRetry() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        var calls = 0
        val request = async(ForegroundSourceRequest()) { runCatching {
            coordinator.execute(owner, "Fixture") { calls++; throw failure }
        } }
        runCurrent()
        val id = coordinator.prompts.value.single().id
        coordinator.dismiss(id)
        coordinator.approveCertificate(id)
        assertEquals(ContentError.Certificate, (request.await().exceptionOrNull() as SourceContentException).code)
        assertEquals(1, calls)
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun openingBackgroundCertificateNoticeStillRequiresTheSeparateConfirmation() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        runCatching { coordinator.execute(owner, "Fixture") { throw failure } }
        val id = coordinator.prompts.value.single().id
        coordinator.approveCertificate(id) // No dialog has been opened yet.
        val opened = async { coordinator.verifyBackground(id) }
        runCurrent()
        assertFalse(opened.isCompleted)
        assertTrue(coordinator.prompts.value.single().confirmingCertificate)
        coVerify(exactly = 0) { verification.complete() }
        coordinator.approveCertificate(id)
        opened.await()
        coVerify(exactly = 1) { verification.complete() }
    }

    @Test fun retiringAnAccountWhileItsCertificateDialogIsOpenPreventsConsent() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.observeRetirement() }
        val request = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { throw failure } } }
        runCurrent()
        val id = coordinator.prompts.value.single().id
        listings.value = listings.value.map { it.copy(metadata = it.metadata.copy(accountGeneration = 1)) }
        runCurrent()
        coordinator.approveCertificate(id)
        assertEquals(ContentError.Unavailable, (request.await().exceptionOrNull() as SourceContentException).code)
        coVerify(exactly = 0) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
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

    @Test fun certificateInsideVerificationBrowserRequiresConsentThenResumesTheBrowser() = runTest {
        for (foreground in listOf(true, false)) {
            clearMocks(verification, answers = false)
            val certificateVerification = mockk<SourceVerification> {
                every { kind } returns null
                every { certificate } returns mockk<hnovel.network.CertificateProblem>()
                coEvery { complete() } returns Unit
            }
            val certificateFailure = SourceContentException(ContentError.Certificate, "browser.verification", verification = certificateVerification)
            var openings = 0
            coEvery { verification.complete() } coAnswers { if (++openings == 1) throw certificateFailure }
            var requests = 0
            suspend fun request(): String = coordinator.execute(owner, "Fixture") {
                if (++requests == 1) throw failure
                "chapter"
            }
            if (!foreground) assertSame(failure, runCatching { request() }.exceptionOrNull())
            val work = async(ForegroundSourceRequest()) {
                if (foreground) request() else coordinator.verifyBackground(coordinator.prompts.value.single().id)
            }
            runCurrent()
            assertFalse(work.isCompleted)
            val prompt = coordinator.prompts.value.single()
            assertSame(certificateVerification.certificate, prompt.certificate)
            assertTrue(prompt.confirmingCertificate)
            assertFalse(prompt.opening)
            coVerify(exactly = 0) { certificateVerification.complete() }
            assertEquals(1, requests)
            coordinator.approveCertificate(prompt.id)
            work.await()
            coVerify(exactly = 1) { certificateVerification.complete() }
            assertEquals(2, openings)
            assertEquals(if (foreground) 2 else 1, requests)
            assertTrue(coordinator.prompts.value.isEmpty())
        }
    }

    @Test fun nestedCertificateDismissalOrAccountRetirementNeverTrustsOrResumes() = runTest {
        val original = listings.value
        for (retire in listOf(false, true)) {
            listings.value = original
            val certificateVerification = mockk<SourceVerification> {
                every { kind } returns null
                every { certificate } returns mockk<hnovel.network.CertificateProblem>()
                coEvery { complete() } returns Unit
            }
            coEvery { verification.complete() } throws SourceContentException(ContentError.Certificate,
                "browser.verification", verification = certificateVerification)
            var requests = 0
            val work = async(ForegroundSourceRequest()) { runCatching {
                coordinator.execute(owner, "Fixture") { requests++; throw failure }
            } }
            runCurrent()
            val id = coordinator.prompts.value.single().id
            if (retire) {
                listings.value = original.map { it.copy(metadata = it.metadata.copy(accountGeneration = 1)) }
                val monitor = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.observeRetirement() }
                runCurrent()
                monitor.cancelAndJoin()
            } else coordinator.dismiss(id)
            coordinator.approveCertificate(id)
            assertEquals(if (retire) ContentError.Unavailable else ContentError.Certificate,
                (work.await().exceptionOrNull() as SourceContentException).code)
            coVerify(exactly = 0) { certificateVerification.complete() }
            assertEquals(1, requests)
            assertTrue(coordinator.prompts.value.isEmpty())
        }
    }

    @Test fun repeatedCertificateInsideVerificationBrowserDoesNotCreateAConsentLoop() = runTest {
        val certificateVerification = mockk<SourceVerification> {
            every { kind } returns null
            every { certificate } returns mockk<hnovel.network.CertificateProblem>()
            coEvery { complete() } returns Unit
        }
        val certificateFailure = SourceContentException(ContentError.Certificate, "browser.verification", verification = certificateVerification)
        coEvery { verification.complete() } throws certificateFailure
        val work = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { throw failure } } }
        runCurrent()
        coordinator.approveCertificate(coordinator.prompts.value.single().id)
        assertSame(certificateFailure, work.await().exceptionOrNull())
        coVerify(exactly = 1) { certificateVerification.complete() }
        coVerify(exactly = 2) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }
}
