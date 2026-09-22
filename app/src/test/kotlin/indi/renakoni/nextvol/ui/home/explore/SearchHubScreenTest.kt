package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.explore.SourceSearchFailure
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.ui.home.explore.search.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import kotlinx.coroutines.flow.flowOf
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
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchHubScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var state by mutableStateOf(SearchHubState())
    private var submitted: String? = null
    private var opened: String? = null
    private var retried = false
    private var back = false
    private var visible by mutableStateOf(true)
    private val source = SearchHubSource(Identifier("fixture", "a"), "Source A", SourceCategory.Adult)

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun render(fontScale: Float = 1f, height: Int = 640) {
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme { Box(Modifier.height(height.dp)) {
                    val holder = rememberSaveableStateHolder()
                    if (visible) holder.SaveableStateProvider("search") {
                    SearchHubScreen(state, onQuery = { state = state.copy(query = it, submittedKeyword = "") },
                        onSearch = { submitted = it }, onScope = { state = state.copy(scope = it) },
                        onDeleteHistory = { value -> state = state.copy(history = state.history - value) },
                        onClearHistory = { state = state.copy(history = emptyList()) },
                        onLoadMore = {}, onStop = {}, onResume = {}, onRetry = { retried = true },
                        onManageSources = {}, onSource = {}, onBook = { opened = it }, onBack = { back = true })
                    }
                } }
            }
        }
    }

    @Test fun hundredHistoryEntriesCanBeReachedSearchedDeletedAndCleared() {
        state = SearchHubState(history = (1..100).map { "History $it" }, sources = listOf(source))
        render()
        compose.onNodeWithText("History 1").assertIsDisplayed()
        compose.onNodeWithText("Source A").assertDoesNotExist()
        compose.onNodeWithTag("search_history").performScrollToNode(hasText("History 100"))
        compose.onNodeWithText("History 100").assertIsDisplayed().performClick()
        assertEquals("History 100", submitted)
        compose.onNodeWithContentDescription("Delete history: History 100").performClick()
        compose.onNodeWithText("History 100").assertDoesNotExist()
        compose.onNodeWithTag("search_history").performScrollToIndex(0)
        compose.onNodeWithText("Clear All").performClick()
        compose.onNodeWithText("History 1").assertDoesNotExist()
        compose.onNodeWithText("Find your next book").assertIsDisplayed()
    }

    @Test fun historyRemainsUsableWithLargeTextAndKeyboardSizedViewport() {
        state = SearchHubState(history = (1..100).map { "A longer history entry $it" }, sources = listOf(source))
        render(fontScale = 1.6f, height = 380)
        compose.onNodeWithTag("search_history").performScrollToNode(hasText("A longer history entry 100"))
        compose.onNodeWithText("A longer history entry 100").assertIsDisplayed().performClick()
        assertEquals("A longer history entry 100", submitted)
        compose.onNodeWithContentDescription("Delete history: A longer history entry 100").assertIsDisplayed().performClick()
        assertFalse(state.history.contains("A longer history entry 100"))
    }

    @Test fun scopePickerShowsGroupsInsteadOfThousandsOfSourceChips() {
        state = SearchHubState(sources = List(1000) { source.copy(id = Identifier("fixture", "$it"), name = "Source $it") })
        render()
        compose.onNodeWithText("All sources").performClick()
        compose.onNodeWithText("r18").performScrollTo().performClick()
        assertEquals(SourceCategory.Adult, state.scope)
        compose.onNodeWithText("Source 0").assertDoesNotExist()
        compose.onNodeWithText("Searchable sources: 1000").assertIsDisplayed()
    }

    @Test fun booksAreVerticalSourceBoundRowsAndEmptySourcesHaveNoPlaceholder() {
        val other = source.copy(id = Identifier("fixture", "empty"), name = "Empty source")
        val info = BookInformation("book", "A book", author = "An author", description = "A short description",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.MIN, isComplete = false)
        state = SearchHubState(query = "book", submittedKeyword = "book", sources = listOf(source, other),
            books = List(30) { index -> SearchHubBook("book-$index", source.id, source.name,
                info.copy(title = "Book $index"), flowOf(Ok(info.copy(title = "Book $index")))) })
        render()
        compose.onNodeWithText("Book 0").assertIsDisplayed()
        compose.onNodeWithText("Empty source").assertDoesNotExist()
        compose.onNodeWithTag("search_results").performScrollToNode(hasText("Book 29"))
        compose.onNodeWithText("Book 29").performClick()
        assertEquals("book-29", opened)
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("Book 29").assertIsDisplayed()
        compose.onNodeWithText("Book 0").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(query = "new", submittedKeyword = "new", revision = state.revision + 1) }
        compose.onNodeWithText("Book 0").assertIsDisplayed()
    }

    @Test fun editingFiltersHistoryAndSubmittingWorksWithTheImeAndAccessibleBackButton() {
        state = SearchHubState(history = listOf("first", "second"), sources = listOf(source))
        render()
        compose.onNodeWithTag("search_query").performTextInput("sec")
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.onNodeWithText("second").assertIsDisplayed()
        compose.onNodeWithTag("search_query").performImeAction()
        assertEquals("sec", submitted)
        compose.onNodeWithContentDescription(activity.get().getString(R.string.sources_back)).performClick()
        assertTrue(back)
    }

    @Test fun failureIsDistinctFromEmptyAndCanBeInspectedAndRetried() {
        state = SearchHubState(query = "book", submittedKeyword = "book", sources = listOf(
            source.copy(failure = SourceSearchFailure(DiscoveryError.Network))))
        render()
        compose.onNodeWithText("No Results Found").assertDoesNotExist()
        compose.onNodeWithText("Failed: 1").performClick()
        compose.onNodeWithText("Source A").assertIsDisplayed()
        compose.onNodeWithText("Retry failed sources").performClick()
        assertTrue(retried)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp-mdpi")
    fun russianScopeAndSearchActionsRemainReachableWithLargeText() {
        state = SearchHubState(query = "book", submittedKeyword = "book", searching = true, total = 1000,
            sources = listOf(source.copy(failure = SourceSearchFailure(DiscoveryError.Network))))
        render(fontScale = 1.6f)
        compose.onNodeWithText(activity.get().getString(R.string.source_range_all)).assertIsDisplayed().performClick()
        compose.onNodeWithText(activity.get().getString(R.string.source_category_adult)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(activity.get().getString(R.string.search_stop)).assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.get().getString(R.string.search_hub_title)).assertIsDisplayed()
    }
}
