package indi.renakoni.nextvol.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import hnovel.content.SourceVerification
import hnovel.network.BrowserChallengeKind
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator
import indi.renakoni.nextvol.data.web.rules.VerificationOwner
import io.mockk.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceVerificationHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val owner = VerificationOwner(Identifier("rules", "fixture"), "revision", 0)
    private val listings = MutableStateFlow(listOf(SourceListing(SourceMetadata(
        WebDataSourceItem(owner.source, "Fixture", ""), emptySet(), revision = owner.revision), SourceStatus.Ready)))
    private val coordinator = SourceVerificationCoordinator(mockk<WebSourceRegistry> {
        every { sources } returns listings
    })
    private val completion = CompletableDeferred<Unit>()
    private val verification = mockk<SourceVerification> {
        every { kind } returns BrowserChallengeKind.Cloudflare
        every { certificate } returns null
        every { origin } returns "https://fixture.test"
        coEvery { complete() } coAnswers { completion.await() }
    }

    @Before fun create() {
        compose.mainClock.autoAdvance = false
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        notice()
        activity.get().setContent { MaterialTheme { SourceVerificationHost(coordinator) } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @After fun destroy() { activity.pause().stop().destroy() }

    private fun notice() = runBlocking {
        val failure = SourceContentException(ContentError.BrowserRequired, "exploreUrl", verification = verification)
        assertSame(failure, runCatching { coordinator.execute<Unit>(owner, "Fixture") { throw failure } }.exceptionOrNull())
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    @Test fun backgroundNoticeDisappearsAfterFourSecondsWithoutOpeningVerification() {
        advance(3_900)
        compose.onNodeWithText("Open verification").assertIsDisplayed()
        advance(200)
        compose.onNodeWithText("Open verification").assertDoesNotExist()
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun aReplacementNoticeGetsItsOwnDisplayTime() {
        advance(3_000)
        compose.runOnIdle { notice() }
        advance(1_500)
        compose.onNodeWithText("Open verification").assertIsDisplayed()
        advance(2_700)
        compose.onNodeWithText("Open verification").assertDoesNotExist()
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun openingVerificationBeforeExpiryKeepsTheRequestAlive() {
        advance(3_000)
        compose.onNodeWithText("Open verification").performClick()
        advance(5_000)
        assertTrue(coordinator.prompts.value.single().opening)
        coVerify(exactly = 1) { verification.complete() }
        compose.runOnIdle { completion.complete(Unit) }
        advance(32)
        assertTrue(coordinator.prompts.value.isEmpty())
    }
}
