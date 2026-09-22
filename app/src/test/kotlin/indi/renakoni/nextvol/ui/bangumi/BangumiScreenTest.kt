package indi.renakoni.nextvol.ui.bangumi

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bangumi.*
import indi.renakoni.nextvol.ui.home.settings.list.ExtensionsSettingsList
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
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
        var pluginsOpened = false
        activity.get().setContent { MaterialTheme { Column { ExtensionsSettingsList({}, { pluginsOpened = true }, { clicked = true }) } } }
        compose.onNodeWithText("Bangumi").performClick()
        assertTrue(clicked)
        val source = compose.onNodeWithText(text(R.string.sources_title)).fetchSemanticsNode().boundsInRoot
        val bangumi = compose.onNodeWithText("Bangumi").fetchSemanticsNode().boundsInRoot
        val plugins = compose.onNodeWithText(text(R.string.settings_plugins)).fetchSemanticsNode().boundsInRoot
        assertTrue(source.top < bangumi.top && bangumi.top < plugins.top)
        compose.onNodeWithText(text(R.string.plugin_install_plugin)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_plugins)).performClick()
        assertTrue(pluginsOpened)
    }

    @Test @Config(qualifiers = "ru-w320dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pluginManagementEntryRemainsReadableAndClickableWithLargeText() {
        var opened = false
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                MaterialTheme(typography = AppTypography) { Column { ExtensionsSettingsList({}, { opened = true }) } }
            }
        }
        val entry = compose.onNodeWithText("Управление плагинами").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        entry.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        entry.performClick()
        assertTrue(opened)
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

    @Test fun globalSyncAndHistorySeparateCompletedAndFailedBooks() {
        val failed = BangumiBinding("Unmatched book", "", "revision", emptyList(), status = BangumiSyncStatus.MATCH_REQUIRED)
        val complete = failed.copy(bookTitle = "Completed book", status = BangumiSyncStatus.SYNCED, lastSyncedAt = 123)
        val state = BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true),
            bindings = listOf(BangumiBindingEntity(1, "one", null, bangumiJson.encodeToString(failed)),
                BangumiBindingEntity(1, "two", 10, bangumiJson.encodeToString(complete))),
            records = listOf(
                BangumiSyncRecord(1, 1, "one", "Unmatched book", 0, 0, BangumiSyncStatus.MATCH_REQUIRED, 123),
                BangumiSyncRecord(2, 1, "two", "Completed book", 2, 2, BangumiSyncStatus.SYNCED, 123),
                BangumiSyncRecord(3, 1, "old", "Previous book", 2, 1, BangumiSyncStatus.OFFLINE, 123, 503, true)))
        var opened: String? = null
        var synced = false
        activity.get().setContent { MaterialTheme { screen(state, null, openBook = { opened = it }, sync = { synced = true }) } }
        compose.onNodeWithContentDescription(text(R.string.bangumi_synced_count) + " 1").assertExists()
        compose.onNodeWithContentDescription(text(R.string.bangumi_unsynced_count) + " 1").assertExists()
        compose.onNodeWithText(text(R.string.bangumi_sync)).performClick()
        assertTrue(synced)
        compose.onNodeWithText(text(R.string.bangumi_records)).performClick()
        compose.onNodeWithText("Completed book").assertExists()
        compose.onNodeWithText("Unmatched book").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.bangumi_records_error)).performClick()
        compose.onNodeWithText("Completed book").assertDoesNotExist()
        compose.onNodeWithText("Unmatched book").assertExists()
        compose.onNodeWithText("Previous book").assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.bangumi_http_failure, 503, text(R.string.bangumi_error_server))).assertExists()
        compose.onNodeWithText(text(R.string.bangumi_correct_match)).performClick()
        assertEquals("one", opened)
        compose.onNodeWithText(text(R.string.bangumi_record_details)).assertDoesNotExist()
    }

    @Test fun historyGroupsBooksByLatestResultAndShowsBothSuccessAndFailureInDetails() {
        val olderFailure = BangumiSyncRecord(9, 1, "a", "Book A", 2, 1, BangumiSyncStatus.OFFLINE, 100, 503)
        val success = olderFailure.copy(id = 10, status = BangumiSyncStatus.SYNCED, timestamp = 200, remote = 2, httpStatus = null)
        val another = success.copy(id = 1, bookId = "b", bookTitle = "Book B", timestamp = 300)
        var state by mutableStateOf(BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true),
            records = listOf(olderFailure, another, success)))
        activity.get().setContent { MaterialTheme { screen(state, null) } }
        compose.onNodeWithText(text(R.string.bangumi_records)).performClick()
        compose.onAllNodesWithText("Book A").assertCountEquals(1)
        compose.onNodeWithText(activity.get().getString(R.string.bangumi_record_count, 2)).assertExists()
        assertTrue(compose.onNodeWithText("Book B").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Book A").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText(text(R.string.bangumi_records_error)).performClick()
        compose.onNodeWithText("Book A").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.bangumi_no_records)).assertExists()
        compose.onNodeWithText(text(R.string.bangumi_records_success)).performClick()
        compose.onNodeWithText("Book A").performClick()
        compose.onNodeWithText(text(R.string.bangumi_record_details)).assertExists()
        compose.onNodeWithText("Book B").assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.bangumi_http_failure, 503, text(R.string.bangumi_error_server))).assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.bangumi_progress, 2, 2)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.sources_back)).performClick()
        compose.runOnIdle { state = state.copy(records = state.records + success.copy(id = 11, timestamp = 400)) }
        assertTrue(compose.onNodeWithText("Book A").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Book B").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText(activity.get().getString(R.string.bangumi_record_count, 3)).assertExists()
        compose.runOnIdle { state = state.copy(records = state.records + olderFailure.copy(id = 12, timestamp = 500)) }
        compose.onNodeWithText("Book A").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.bangumi_records_error)).performClick()
        compose.onNodeWithText("Book A").assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.bangumi_record_count, 4)).assertExists()
    }

    @Test fun sameTitlesStaySeparateAndAccountChangesLeaveThePreviousHistory() {
        val record = BangumiSyncRecord(1, 1, "one", "Same title", 1, 1, BangumiSyncStatus.SYNCED, 100)
        var state by mutableStateOf(BangumiUiState(account = BangumiAccountState(BangumiUser(1, "first"), true),
            records = listOf(record, record.copy(id = 2, bookId = "two"))))
        activity.get().setContent { MaterialTheme { screen(state, null) } }
        compose.onNodeWithText(text(R.string.bangumi_records)).performClick()
        compose.onAllNodesWithText("Same title").assertCountEquals(2)
        compose.runOnIdle { state = state.copy(account = BangumiAccountState(BangumiUser(2, "second"), true), records = emptyList()) }
        compose.onNodeWithText("Same title").assertDoesNotExist()
        compose.onNodeWithText("second").assertExists()
        compose.onNodeWithText(text(R.string.bangumi_sync)).assertExists()
    }

    @Test fun historyDetailsSurviveRecreationAndBackReturnsToTheirCategory() {
        val record = BangumiSyncRecord(1, 1, "one", "Failed book", 2, 1, BangumiSyncStatus.OFFLINE, 100, 503)
        val state = BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true), records = listOf(record))
        fun show() { activity.get().setContent { MaterialTheme { screen(state, null) } } }
        show()
        compose.onNodeWithText(text(R.string.bangumi_records)).performClick()
        compose.onNodeWithText(text(R.string.bangumi_records_error)).performClick()
        compose.onNodeWithText("Failed book").performClick()
        compose.runOnIdle {
            activity.recreate()
            activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        show()
        compose.onNodeWithText(text(R.string.bangumi_record_details)).assertExists()
        compose.onNodeWithText("Failed book").assertExists()
        compose.onNodeWithContentDescription(text(R.string.sources_back)).performClick()
        compose.onNodeWithText(text(R.string.bangumi_records_error)).assertIsSelected()
        compose.onNodeWithText("Failed book").assertExists()
        compose.onNodeWithContentDescription(text(R.string.sources_back)).performClick()
        compose.onNodeWithText(text(R.string.bangumi_sync)).assertExists()
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

    @Test fun correctionAcceptsAPastedBangumiLinkAndDisplaysSpecificLinkErrors() {
        var state by mutableStateOf(BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true)))
        var searched = false
        activity.get().setContent { MaterialTheme {
            BangumiScreen(state, "book", {}, {}, {}, {}, {}, { state = state.copy(query = it, error = null) }, { searched = true }, {}, {}, {},
                { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, {})
        } }
        compose.onNodeWithText(text(R.string.bangumi_correct_match)).assertExists()
        compose.onNodeWithText(text(R.string.bangumi_paste_link)).assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("http://bangumi.tv/subject/123")
        compose.onNodeWithText(text(R.string.bangumi_search)).performClick()
        assertTrue(searched)
        assertEquals("http://bangumi.tv/subject/123", state.query)
        compose.runOnIdle { state = state.copy(error = R.string.bangumi_invalid_link) }
        compose.onNodeWithText(text(R.string.bangumi_invalid_link)).assertExists()
        compose.onNode(hasSetTextAction()).performClick().performTextClearance()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText(text(R.string.bangumi_invalid_link)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("https://bgm.tv/subject/456")
        assertEquals("https://bgm.tv/subject/456", state.query)
    }

    @Composable private fun screen(state: BangumiUiState, bookId: String?, connect: (String) -> Unit = {},
        disconnect: () -> Unit = {}, complete: (String, Boolean) -> Unit = { _, _ -> }, confirm: () -> Unit = {},
        openBook: (String) -> Unit = {}, sync: () -> Unit = {},
        mapping: (String, String?) -> Unit = { _, _ -> }) {
        BangumiScreen(state, bookId, {}, {}, openBook, connect, disconnect, {}, {}, {}, {}, sync,
            mapping, complete, { _, _ -> }, {}, confirm, {})
    }
}
