package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.BookshelfBookItem
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.BookshelfUiState
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalMaterial3Api::class)
class BookshelfLayoutUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val listState = LazyListState()
    private val gridState = LazyGridState()
    private val opened = mutableListOf<String>()
    private val state = MutableBookshelfHomeUiState(
        changeLayout = { layout -> stateLayout(layout) },
        onBookClick = { opened.add(it) },
        onEnableSelectMode = { enableSelection() },
        changeBookSelectState = { toggleSelection(it) }
    )

    private fun stateLayout(layout: BookshelfLayout) { state.layout = layout }
    private fun enableSelection() { state.selectMode = true }
    private fun toggleSelection(id: String) {
        if (!state.selectedBookIds.remove(id)) state.selectedBookIds.add(id)
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun tearDown() { activity.pause().stop().destroy() }

    private fun book(source: String, remoteId: String, title: String): Pair<String, Flow<Result<BookshelfBookItem, WebRequestError>>> {
        val id = SourceBookId(Identifier("fixture", source), remoteId).storageKey
        return id to flowOf(Ok(BookshelfBookItem(id, null, BookInformation(
            id = id, title = title, author = "Author", description = "Description", publishingHouse = "",
            wordCount = WordCount(100), lastUpdated = LocalDateTime.of(2026, 9, 10, 0, 0), isComplete = false
        ))))
    }

    private fun show(books: List<Pair<String, Flow<Result<BookshelfBookItem, WebRequestError>>>>, fontScale: Float = 1f) {
        state.selectedBookshelfId = 1
        state.bookshelfList = listOf(BookshelfUiState(1, "Shelf", BookshelfSortType.Default, false, false, false, books, emptyList(), emptyList()))
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        val behavior = TopAppBarDefaults.pinnedScrollBehavior()
                        BookshelfHomeTopBar(behavior, MaterialTheme.colorScheme.surface, state, {}, {}, {}, {}, {})
                        BookshelfHomeContent(state, listState, gridState, behavior)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun toggleLayout() {
        val resource = if (state.layout == BookshelfLayout.List) R.string.bookshelf_layout_switch_grid else R.string.bookshelf_layout_switch_list
        compose.onNodeWithContentDescription(activity.get().getString(resource)).performClick()
        compose.waitForIdle()
    }

    @Test fun switchingDuringSelectionKeepsSourceQualifiedBooksAndActions() {
        val a = book("a", "same", "Alpha")
        val b = book("b", "same", "Beta")
        show(listOf(a, b))
        compose.onNode(hasClickAction() and hasText("Alpha")).performTouchInput { longClick() }
        toggleLayout()
        compose.onNode(hasClickAction() and hasText("Beta")).performClick()
        compose.runOnIdle {
            assertEquals(listOf(a.first, b.first), state.selectedBookIds)
            assertTrue(state.selectMode)
            assertEquals(BookshelfSortType.Default, state.selectedBookshelf!!.sortType)
            assertTrue(opened.isEmpty())
        }
        toggleLayout()
        compose.runOnIdle { assertEquals(listOf(a.first, b.first), state.selectedBookIds) }
    }

    @Test fun switchingAlignsVisibleBookAfterOrderChangesAndKeepsSeparateScrollStates() {
        val books = (0 until 30).map { book("a", "$it", "Book $it") }
        show(books)
        compose.runOnIdle { runBlocking { listState.scrollToItem(12) } }
        val anchor = compose.runOnIdle { listState.layoutInfo.visibleItemsInfo.first { it.contentType == "book_card" }.key }
        compose.runOnIdle {
            state.bookshelfList = listOf(state.selectedBookshelf!!.copy(
                allBookFlows = books.reversed(), sortType = BookshelfSortType.Name, sortReversed = true
            ))
        }
        compose.waitForIdle()
        toggleLayout()
        compose.runOnIdle {
            assertTrue(gridState.layoutInfo.visibleItemsInfo.any { it.key == anchor })
            assertTrue(listState.firstVisibleItemIndex > 0)
            assertEquals(BookshelfSortType.Name, state.selectedBookshelf!!.sortType)
            assertTrue(state.selectedBookshelf!!.sortReversed)
        }
        compose.runOnIdle { runBlocking { gridState.scrollToItem(20) } }
        val gridAnchor = compose.runOnIdle { gridState.layoutInfo.visibleItemsInfo.first { it.contentType == "book_card" }.key }
        toggleLayout()
        compose.runOnIdle { assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.key == gridAnchor }) }
    }

    @Test fun phoneGridUsesTwoColumnsAndFullWidthHeadersWithMissingCovers() {
        state.layout = BookshelfLayout.Grid
        show((0 until 6).map { book("a", "$it", "A very long book title that needs to wrap and ellipsize $it") })
        compose.runOnIdle {
            val visible = gridState.layoutInfo.visibleItemsInfo
            assertEquals(360, visible.first { it.key == "book" }.size.width)
            assertEquals(2, visible.filter { it.contentType == "book_card" }.map { it.column }.distinct().size)
            assertTrue(visible.all { it.offset.x >= 0 && it.offset.x + it.size.width <= 360 })
        }
    }

    @Test fun largeFontUsesOneColumnAndHeaderRemainsVisible() {
        state.layout = BookshelfLayout.Grid
        show((0 until 6).map { book("a", "$it", "Long title $it") }, fontScale = 2f)
        compose.runOnIdle {
            val visible = gridState.layoutInfo.visibleItemsInfo
            assertEquals(1, visible.filter { it.contentType == "book_card" }.map { it.column }.distinct().size)
            assertEquals(360, visible.first { it.key == "book" }.size.width)
        }
    }

    @Test
    @Config(qualifiers = "w1000dp-h600dp-land-mdpi")
    fun wideLandscapeGridAddsColumns() {
        state.layout = BookshelfLayout.Grid
        show((0 until 12).map { book("a", "$it", "Book $it") })
        compose.runOnIdle {
            val visible = gridState.layoutInfo.visibleItemsInfo
            assertEquals(1000, visible.first { it.key == "book" }.size.width)
            assertEquals(6, visible.filter { it.contentType == "book_card" }.map { it.column }.distinct().size)
        }
    }

    @Test fun pendingAndFailedBooksDoNotBlockOtherCardsOrLoseSelection() {
        val ready = book("a", "ready", "Ready")
        val pending = book("a", "pending", "Pending").first to MutableSharedFlow<Result<BookshelfBookItem, WebRequestError>>()
        val failed = book("b", "failed", "Failed").first to flowOf<Result<BookshelfBookItem, WebRequestError>>(Err(WebRequestError("Failure", "Fixture")))
        state.layout = BookshelfLayout.Grid
        show(listOf(ready, pending, failed))
        compose.onNode(hasClickAction() and hasText("Ready")).performClick()
        compose.runOnIdle {
            assertEquals(listOf(ready.first), opened)
            assertEquals(5, gridState.layoutInfo.totalItemsCount)
            runBlocking { gridState.scrollToItem(3) }
        }
        compose.onNodeWithText(activity.get().getString(R.string.bookshelf_layout_load_error)).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf(failed.first), state.selectedBookIds) }
        toggleLayout()
        compose.onNodeWithText(activity.get().getString(R.string.bookshelf_layout_load_error)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(failed.first), state.selectedBookIds) }
    }

    @Test fun repeatedBooksInSectionsHaveDistinctKeysAndCollapseIndependently() {
        val books = listOf(book("a", "1", "Alpha"), book("b", "1", "Beta"))
        state.layout = BookshelfLayout.Grid
        show(books)
        compose.runOnIdle {
            state.bookshelfList = listOf(state.selectedBookshelf!!.copy(pinnedBookFlows = books.take(1), updatedBookFlows = books.take(1)))
            state.selectedBookIds.add(books.first().first)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(8, gridState.layoutInfo.totalItemsCount)
            state.updatedExpanded = false
        }
        compose.waitForIdle()
        toggleLayout()
        compose.runOnIdle {
            assertEquals(7, listState.layoutInfo.totalItemsCount)
            assertFalse(state.updatedExpanded)
            assertTrue(state.pinnedExpanded)
            assertEquals(listOf(books.first().first), state.selectedBookIds)
        }
    }

    @Test fun emptyShelfKeepsItsEmptyStateAcrossLayouts() {
        show(emptyList())
        val emptyTitle = activity.get().getString(R.string.nothing_here)
        compose.onNodeWithText(emptyTitle).assertIsDisplayed()
        toggleLayout()
        compose.onNodeWithText(emptyTitle).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(state.selectedBookshelf!!.allBookFlows.isEmpty())
            assertTrue(state.selectedBookIds.isEmpty())
        }
    }
}
