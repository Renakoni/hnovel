package indi.renakoni.nextvol.ui.bangumi

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bangumi.*
import indi.renakoni.nextvol.ui.home.settings.list.ExtensionsSettingsList
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
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
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BangumiScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun close() { activity.pause().stop().destroy() }
    private fun text(id: Int) = activity.get().getString(id)

    @Test fun extensionsEntryOpensBangumi() {
        var clicked = false
        activity.get().setContent { MaterialTheme { Column { ExtensionsSettingsList({}, {}, { clicked = true }) } } }
        compose.onNodeWithText("Bangumi").performClick()
        assertTrue(clicked)
        val source = compose.onNodeWithText(text(R.string.sources_title)).fetchSemanticsNode().boundsInRoot
        val bangumi = compose.onNodeWithText("Bangumi").fetchSemanticsNode().boundsInRoot
        val plugins = compose.onNodeWithText(text(R.string.plugin_install_plugin)).fetchSemanticsNode().boundsInRoot
        assertTrue(source.top < bangumi.top && bangumi.top < plugins.top)
    }

    @Test fun tokenIsClearedImmediatelyAfterConnectingAndDisconnectRequiresConfirmation() {
        var submitted: String? = null
        var disconnected = false
        val state = BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), loaded = true))
        activity.get().setContent { MaterialTheme { screen(state, null,
            connect = { submitted = it }, disconnect = { disconnected = true }) } }
        compose.onNodeWithText(text(R.string.bangumi_change_token)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.bangumi_account_actions)).performClick()
        compose.onNodeWithText(text(R.string.bangumi_change_token)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("test-only-token")
        compose.onNodeWithText(text(R.string.bangumi_connect)).performClick()
        assertEquals("test-only-token", submitted)
        compose.onNode(hasSetTextAction()).assertTextContains("")
        compose.onNodeWithContentDescription(text(R.string.bangumi_account_actions)).performClick()
        compose.onNodeWithText(text(R.string.bangumi_disconnect)).performClick()
        assertFalse(disconnected)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        assertTrue(disconnected)
    }

    @Test fun incompleteVolumesNeedConfirmationAndSingleBooksCannotAcceptMultiplePublications() {
        val book = BookInformation("book", "Novel", "", Uri.EMPTY, "Author", "", emptyList(), "", WordCount(0), LocalDateTime.MIN, false)
        val row = BangumiVolumeMapping("one", "Volume one", "one", setOf("chapter"), false)
        var state by mutableStateOf(BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true),
            preview = BangumiBookPreview(book, BangumiSubject(1, name = "Novel", volumes = 1), listOf(row), null, 1, "generation"),
            mapping = listOf(row)))
        var complete = false
        var confirmed = false
        activity.get().setContent { MaterialTheme { screen(state, "book", complete = { _, value -> complete = value }, confirm = { confirmed = true }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.bangumi_complete_volume)))
        compose.onNodeWithText(text(R.string.bangumi_complete_volume)).performClick()
        assertTrue(complete)
        compose.runOnIdle {
            val mapping = listOf(row, row.copy(volumeId = "two", editionKey = "two"))
            state = state.copy(mapping = mapping, preview = state.preview!!.copy(mapping = mapping))
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.bangumi_confirm)))
        compose.onNodeWithText(text(R.string.bangumi_confirm)).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(mapping = listOf(row.copy(editionKey = null))) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.bangumi_confirm)))
        compose.onNodeWithText(text(R.string.bangumi_confirm)).assertIsNotEnabled()
        assertFalse(confirmed)
    }

    @Test fun settingsCentralizeBookSelectionErrorsAndPersistedHistory() {
        val binding = BangumiBinding("Linked book", "Series", "revision", emptyList(), status = BangumiSyncStatus.OFFLINE)
        val state = BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true),
            localBooks = listOf(BangumiLocalBook("one", "Available book"), BangumiLocalBook("two", "Linked book")),
            bindings = listOf(BangumiBindingEntity(1, "two", 10, bangumiJson.encodeToString(binding))),
            records = listOf(BangumiSyncRecord(1, 1, "old", "Previous book", 2, 1, BangumiSyncStatus.OFFLINE, 123, 503, true)))
        var opened: String? = null
        var retried = false
        activity.get().setContent { MaterialTheme { screen(state, null, openBook = { opened = it }, retryFailures = { retried = true }) } }
        compose.onNodeWithContentDescription(text(R.string.bangumi_pending_count) + " 1").assertExists()
        compose.onNodeWithContentDescription(text(R.string.bangumi_attention_count) + " 0").assertExists()
        compose.onNodeWithText(text(R.string.bangumi_retry_failures)).performClick()
        assertTrue(retried)
        compose.onNodeWithText(text(R.string.bangumi_records)).performClick()
        compose.onNodeWithText("Previous book").assertExists()
        compose.onNodeWithText(text(R.string.bangumi_status_offline)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.bangumi_http_failure, 503, text(R.string.bangumi_error_server))).assertExists()
        compose.onNodeWithText(text(R.string.bangumi_pending_confirmation)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.sources_back)).performClick()
        compose.onNodeWithText(text(R.string.bangumi_add_binding)).performClick()
        compose.onNodeWithText("Linked book").assertDoesNotExist()
        compose.onNodeWithText("Available book").performClick()
        assertEquals("one", opened)
    }

    @Test fun reenablingAVolumePreservesItsConfirmedPublicationIdentity() {
        val book = BookInformation("book", "Novel", "", Uri.EMPTY, "Author", "", emptyList(), "", WordCount(0), LocalDateTime.MIN, false)
        val row = BangumiVolumeMapping("one", "Volume one", "subject:11", setOf("chapter"), true)
        var state by mutableStateOf(BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true),
            preview = BangumiBookPreview(book, BangumiSubject(1, name = "Novel", volumes = 1), listOf(row), null, 1, "generation"),
            mapping = listOf(row)))
        activity.get().setContent { MaterialTheme { screen(state, "book", mapping = { id, key ->
            state = state.copy(mapping = state.mapping.map { if (it.volumeId == id) it.copy(editionKey = key) else it })
        }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(row.title))
        compose.onNodeWithText(row.title).performClick()
        assertNull(state.mapping.single().editionKey)
        compose.onNodeWithText(row.title).performClick()
        assertEquals("subject:11", state.mapping.single().editionKey)
    }

    @Test fun bookMenuKeepsUnlinkBehindConfirmation() {
        val binding = BangumiBinding("Linked book", "Series", "revision", emptyList(), status = BangumiSyncStatus.AUTH_REQUIRED)
        val state = BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true),
            bindings = listOf(BangumiBindingEntity(1, "book", 10, bangumiJson.encodeToString(binding))))
        var unlinked: String? = null
        activity.get().setContent { MaterialTheme { screen(state, null, unlink = { unlinked = it }) } }
        compose.onNodeWithContentDescription(text(R.string.bangumi_attention_count) + " 1").assertExists()
        compose.onNodeWithText(text(R.string.bangumi_unlink)).assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(activity.get().getString(R.string.bangumi_book_actions, "Linked book")))
        compose.onNodeWithContentDescription(activity.get().getString(R.string.bangumi_book_actions, "Linked book")).performClick()
        compose.onNodeWithText(text(R.string.bangumi_unlink)).performClick()
        assertNull(unlinked)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        assertEquals("book", unlinked)
    }

    @Composable private fun screen(state: BangumiUiState, bookId: String?, connect: (String) -> Unit = {},
        disconnect: () -> Unit = {}, complete: (String, Boolean) -> Unit = { _, _ -> }, confirm: () -> Unit = {},
        openBook: (String) -> Unit = {}, retryFailures: () -> Unit = {}, unlink: (String) -> Unit = {},
        mapping: (String, String?) -> Unit = { _, _ -> }) {
        BangumiScreen(state, bookId, {}, {}, openBook, connect, disconnect, {}, {}, {}, {}, unlink, retryFailures,
            mapping, complete, { _, _ -> }, {}, confirm, {})
    }
}
