package indi.dmzz_yyhyy.lightnovelreader.ui.components

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BookCoverTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun blankAddressHasLocalizedAccessibleArtworkAndStableBoundsAtLargeFont() {
        var fallback: Boolean? = null
        activity.get().setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Cover("fixture", 72.dp, 108.dp, Uri.parse("  "), "", onFallbackChanged = { fallback = it })
            }
        } }
        compose.onNodeWithContentDescription("Cover of Untitled").assertIsDisplayed()
            .assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(108.dp)
        compose.runOnIdle { assertEquals(true, fallback) }
    }

    @Test fun failedRemoteCoverCanRecoverOnTheSameAddressWithoutCachingItsSubstitute() {
        val calls = AtomicInteger()
        val error = IOException("fixture")
        val loader = ImageLoader.Builder(activity.get()).components {
            add(Interceptor { chain ->
                if (calls.incrementAndGet() == 1) ErrorResult(null, chain.request, error)
                else SuccessResult(createBitmap(60, 87).asImage(), chain.request, DataSource.MEMORY)
            })
        }.build()
        val request = ImageRequest.Builder(activity.get()).data("https://fixture.invalid/cover").size(60, 87).build()
        var visit by mutableIntStateOf(0)
        var fallback: Boolean? = null
        activity.get().setContent { MaterialTheme {
            key(visit) { BookCoverImage(request, "fixture", 72.dp, 108.dp, "A book", "An author",
                onFallbackChanged = { fallback = it }, imageLoader = loader) }
        } }
        compose.waitForIdle()
        compose.waitUntil(10_000) { fallback == true }
        compose.onAllNodesWithContentDescription("Cover of A book").onFirst().assertIsDisplayed()
        compose.runOnIdle { fallback = null; visit++ }
        compose.onAllNodesWithContentDescription("Cover of A book").onFirst().assertExists()
        compose.waitUntil(10_000) { fallback == false }
        assertEquals(2, calls.get())
        loader.shutdown()
    }
}
