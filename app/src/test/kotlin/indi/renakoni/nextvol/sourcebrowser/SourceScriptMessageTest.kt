package indi.renakoni.nextvol.sourcebrowser

import android.app.Application
import hnovel.network.RequestCommitGuard
import indi.renakoni.nextvol.data.web.AndroidSourceNetworks
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceScriptMessageTest {
    @Test fun leadingBlankLinesDoNotHideScriptFeedbackAndRetiredCallsCannotDisplay(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowToast.reset()
        try {
            val browser = AndroidSourceBrowser(RuntimeEnvironment.getApplication(), mockk<AndroidSourceNetworks>(relaxed = true))
            browser.showMessage("\n\n  Debug enabled\nInstructions  ", true, RequestCommitGuard { it() })
            assertEquals("Debug enabled\nInstructions", ShadowToast.getTextOfLatestToast())
            assertTrue(runCatching {
                browser.showMessage("retired", false, RequestCommitGuard { error("retired") })
            }.isFailure)
            assertEquals("Debug enabled\nInstructions", ShadowToast.getTextOfLatestToast())
        } finally { ShadowToast.reset(); Dispatchers.resetMain() }
    }
}
