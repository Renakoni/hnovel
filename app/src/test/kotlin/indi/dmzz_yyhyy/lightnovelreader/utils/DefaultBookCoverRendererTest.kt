package indi.dmzz_yyhyy.lightnovelreader.utils

import android.app.Application
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DefaultBookCoverRendererTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun titleHashCollisionAndMetadataChangesNeverReuseAnotherCover() {
        assertEquals("FB".hashCode(), "Ea".hashCode())
        fun uri(title: String = "FB", id: String = "book", author: String = "author") =
            DefaultBookCoverRenderer.cacheUri(context, title, id, author)
        assertEquals(uri(), uri())
        assertEquals(4, setOf(uri(), uri("Ea"), uri(id = "other"), uri(author = "another")).size)
    }
    @Test fun untitledArtworkUsesTheRequestedLocaleAndItsOwnCacheEntry() {
        val zh = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        assertEquals("Untitled", DefaultBookCoverRenderer.displayTitle(context, "  "))
        assertEquals("未命名", DefaultBookCoverRenderer.displayTitle(zh, "  "))
        assertNotEquals(DefaultBookCoverRenderer.cacheUri(context, ""), DefaultBookCoverRenderer.cacheUri(zh, ""))
    }
}
