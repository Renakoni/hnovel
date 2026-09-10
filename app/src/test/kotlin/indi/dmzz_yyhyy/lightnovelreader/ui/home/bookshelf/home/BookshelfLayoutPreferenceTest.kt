package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.BookshelfUiState
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfLayoutPreferenceTest {
    @Test
    fun layoutPersistsAcrossModelsWithoutChangingShelfOrSelectionAndUnknownValuesFallBack() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, LightNovelReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        val users = UserDataRepository(db.userDataDao())
        val preference = users.stringUserData(UserDataPath.Settings.Display.BookshelfLayout.path)
        val store = ViewModelStore()
        fun model(key: String) = BookshelfHomeViewModel(context, mockk(), mockk(), users, mockk()).also { store.put(key, it) }
        suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5000) {
            while (!condition()) { main.scheduler.runCurrent(); delay(1) }
        }
        try {
            val first = model("first")
            val state = first.uiState as MutableBookshelfHomeUiState
            val shelf = BookshelfUiState(7, "Shelf", BookshelfSortType.Name, true, true, true, emptyList(), emptyList(), emptyList())
            state.bookshelfList = listOf(shelf)
            state.selectedBookshelfId = shelf.id
            state.selectMode = true
            state.selectedBookIds.addAll(listOf("source-a", "source-b"))
            state.updatedExpanded = false
            assertEquals(BookshelfLayout.List, state.layout)
            first.changeLayout(BookshelfLayout.Grid)
            awaitCondition { state.layout == BookshelfLayout.Grid }
            assertEquals("Grid", preference.get())
            assertSame(shelf, state.selectedBookshelf)
            assertEquals(listOf("source-a", "source-b"), state.selectedBookIds)
            assertTrue(state.selectMode)
            assertFalse(state.updatedExpanded)

            store.clear()
            val restored = model("restored")
            awaitCondition { restored.uiState.layout == BookshelfLayout.Grid }
            preference.set("unknown-future-layout")
            awaitCondition { restored.uiState.layout == BookshelfLayout.List }
        } finally {
            store.clear()
            db.close()
            Dispatchers.resetMain()
        }
    }
}
