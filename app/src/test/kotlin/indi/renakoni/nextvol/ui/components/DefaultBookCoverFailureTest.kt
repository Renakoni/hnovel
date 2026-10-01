package indi.renakoni.nextvol.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefaultBookCoverFailureTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun rasterFailureKeepsTheCoverBackgroundVisible() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val failed = AtomicBoolean()
        val loader = ImageLoader.Builder(activity.get()).components {
            add(Interceptor { chain ->
                failed.set(true)
                ErrorResult(null, chain.request, IOException("raster failure"))
            })
        }.build()
        try {
            activity.get().setContent { MaterialTheme {
                DefaultBookCover("A title", 72.dp, 108.dp, "book", imageLoader = loader)
            } }
            compose.waitUntil(5_000) { failed.get() }
            compose.waitForIdle()
            val pixels = compose.onNodeWithContentDescription("Cover of A title").captureToImage().toPixelMap()
            val expected = Color(DefaultBookCoverRenderer.backgroundColor(activity.get(),
                DefaultBookCoverRenderer.Text("book", "A title")))
            assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2])
        } finally {
            activity.pause().stop().destroy()
            loader.shutdown()
        }
    }
}
