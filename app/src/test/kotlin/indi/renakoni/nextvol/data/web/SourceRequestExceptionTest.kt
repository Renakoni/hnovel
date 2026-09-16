package indi.renakoni.nextvol.data.web

import hnovel.content.ContentError
import hnovel.content.SourceContentException
import hnovel.content.SourceVerification
import hnovel.network.BrowserChallengeKind
import indi.renakoni.nextvol.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceRequestExceptionTest {
    @Test fun onlyAuthenticationFailuresAskForLogin() {
        for (kind in BrowserChallengeKind.entries) {
            val ticket = mockk<SourceVerification> { every { this@mockk.kind } returns kind }
            assertEquals(if (kind == BrowserChallengeKind.Login) R.string.sources_login_required else R.string.sources_verification_required,
                sourceFailureMessage(SourceContentException(ContentError.BrowserRequired, "searchUrl", verification = ticket)))
        }
        assertEquals(R.string.sources_login_required, sourceFailureMessage(SourceContentException(ContentError.LoginRequired, "loginUrl")))
        assertEquals(R.string.sources_verification_incomplete,
            sourceFailureMessage(SourceContentException(ContentError.BrowserRequired, "loginUrl")))
        for (code in listOf(ContentError.Dns, ContentError.Network, ContentError.PermissionDenied, ContentError.AddressDenied,
            ContentError.RouteUnavailable, ContentError.RouteUnsupported, ContentError.Unavailable, ContentError.InvalidRule)) {
            val message = sourceFailureMessage(SourceContentException(code, "loginUrl"))
            assertNotEquals(R.string.sources_login_required, message)
            assertNotEquals(R.string.sources_verification_required, message)
        }
    }
}
