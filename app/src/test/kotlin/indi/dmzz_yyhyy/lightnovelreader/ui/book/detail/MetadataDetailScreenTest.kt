package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.NavHostController
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.download.BookDownloadState
import indi.dmzz_yyhyy.lightnovelreader.data.download.BookDownloadPhase
import indi.dmzz_yyhyy.lightnovelreader.data.download.MutableDownloadItem
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadType
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySources
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalClaimSnackbarHost
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalSnackbarHost
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.ui.LocalNavController
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
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MetadataDetailScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun show(state: MutableDetailUiState, retry: () -> Unit = {}, bookmark: (String) -> Unit = {}, cache: (String) -> Unit = {}) {
        activity.get().setContent {
            CompositionLocalProvider(LocalNavController provides NavHostController(activity.get()),
                LocalSnackbarHost provides SnackbarHostState(), LocalClaimSnackbarHost provides {}) {
                MaterialTheme { DetailScreen(state, {}, {}, {}, {}, cache, bookmark, {}, {}, {}, retry) }
            }
        }
    }

    @Test fun metadataShowsEditionAndBasicInfoWithoutReadingDirectoryCacheOrExport() {
        val key = SourceBookId(ZLibrarySources.ID, "17/abcdef").storageKey
        val book = BookInformation(key, "Metadata book", subtitle = "Chinese · EPUB · 2020", author = "Author",
            description = "A real book description", publishingHouse = "Publisher", wordCount = WordCount(0),
            lastUpdated = LocalDateTime.of(1970, 1, 1, 0, 0), isComplete = false)
        val state = MutableDetailUiState().apply { bookInformation = Ok(book); metadataOnly = true }
        var saved: String? = null
        show(state, bookmark = { saved = it })
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(book.subtitle).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(activity.get().getString(R.string.source_metadata_only)))
        compose.onNodeWithText(activity.get().getString(R.string.source_metadata_only)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.detail_contents)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.start_reading)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.cached_false)).assertDoesNotExist()
        compose.onNodeWithContentDescription("export").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(activity.get().getString(R.string.add_to_bookshelf)))
        compose.onNodeWithText(activity.get().getString(R.string.add_to_bookshelf)).performClick()
        assertEquals(key, saved)
        compose.onNodeWithText(activity.get().getString(R.string.action_show_info)).performClick()
        compose.onNodeWithText("17/abcdef").assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.detail_info_stats)).assertDoesNotExist()
    }

    @Test fun failedMetadataHasAnExplicitRetryAction() {
        val state = MutableDetailUiState().apply { bookInformation = Err(WebRequestError("Z-Library", "Invalid response")) }
        var retries = 0
        show(state, retry = { retries++ })
        compose.onNodeWithText("Invalid response").assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.discovery_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun completedDownloadsCanUpdateAndFailuresCanRetryWithoutNegativeProgress() {
        val key = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", "a"), "book").storageKey
        val state = MutableDetailUiState().apply {
            bookInformation = Ok(BookInformation(key, "Book", author = "Author", description = "",
                publishingHouse = "", wordCount = WordCount(1), lastUpdated = LocalDateTime.of(2026, 9, 15, 0, 0), isComplete = false))
            canCache = true
            downloadState = BookDownloadState(BookDownloadPhase.Complete, 3, 3)
        }
        var requests = 0
        show(state, cache = { assertEquals(key, it); requests++ })
        val update = activity.get().getString(R.string.book_download_check_updates)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(update))
        compose.onNodeWithText(update).assertIsEnabled().performClick()
        assertEquals(1, requests)
        compose.runOnIdle {
            state.downloadState = BookDownloadState(BookDownloadPhase.Updating, 1, 3)
            state.downloadItem = MutableDownloadItem(DownloadType.CACHE, key, kotlinx.coroutines.flow.emptyFlow()).apply { progress = 0.5f }
        }
        compose.onNodeWithText(activity.get().getString(R.string.book_download_updating)).assertIsNotEnabled()
        compose.onNodeWithText("50%").assertExists()
        compose.runOnIdle {
            state.downloadState = BookDownloadState(BookDownloadPhase.Failed, 1, 3)
            (state.downloadItem as MutableDownloadItem).progress = -1f
        }
        compose.onNodeWithText(activity.get().getString(R.string.book_download_retry)).assertIsEnabled().performClick()
        compose.onNodeWithText("-100%").assertDoesNotExist()
        assertEquals(2, requests)
        compose.runOnIdle {
            state.downloadState = BookDownloadState(BookDownloadPhase.Complete, 3, 3)
            state.canCache = false
        }
        compose.onNodeWithText(activity.get().getString(R.string.cached)).assertIsNotEnabled()
        compose.onNodeWithText(update).assertDoesNotExist()
    }
}
