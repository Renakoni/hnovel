package indi.renakoni.nextvol.data.web.rules

import hnovel.content.*
import hnovel.execution.ExecutionAuthority
import hnovel.network.CertificateProblem
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
class SourceLoginVerificationTest {
    @Test fun loginFormAndSubmissionBothWaitForCertificateConsentBeforeRetrying() = runTest {
        for (submit in listOf(false, true)) {
            val id = Identifier("rules", "login-certificate")
            val listings = MutableStateFlow(listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Fixture", ""),
                emptySet(), revision = "revision"), SourceStatus.Ready)))
            val coordinator = SourceVerificationCoordinator(mockk { every { sources } returns listings })
            val verification = mockk<SourceVerification> {
                every { kind } returns null
                every { certificate } returns mockk<CertificateProblem>()
                coEvery { complete() } returns Unit
            }
            val failure = SourceContentException(ContentError.Certificate, "login", verification = verification)
            val rules = mockk<RuleSource>()
            var calls = 0
            if (submit) coEvery { rules.login(any(), any(), any()) } coAnswers { if (++calls == 1) throw failure }
            else coEvery { rules.loginForm() } coAnswers {
                if (++calls == 1) throw failure
                LoginForm(emptyList(), null)
            }
            val target = RuleLoginTarget(id, "revision", 0, rules, mockk())
            val sources = mockk<ImportedRuleSources> {
                coEvery { loginTarget(id) } returns target
                coEvery { installedSources() } returns emptyList()
            }
            val login = SourceLoginService(sources, SourceSessionManager(ExecutionAuthority()), coordinator)
            val attempt = LoginAttempt(id, 0, "revision", rules)
            val request = async(ForegroundSourceRequest()) {
                if (submit) login.submit(attempt, mapOf("user" to "fixture", "password" to "synthetic-secret"))
                else login.form(attempt)
            }
            runCurrent()
            assertFalse(request.isCompleted)
            assertEquals(1, calls)
            coVerify(exactly = 0) { verification.complete() }
            coordinator.approveCertificate(coordinator.prompts.value.single().id)
            request.await()
            assertEquals(2, calls)
            coVerify(exactly = 1) { verification.complete() }
            assertTrue(coordinator.prompts.value.isEmpty())
        }
    }
}
