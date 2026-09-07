package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.app.Application
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderSettingsBoundaryTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun settingAdapterPreservesDefaultsAndStoragePaths() {
        val state = SettingState(UserDataRepository(InMemoryUserDataDao()), scope)

        assertEquals(15f, state.fontSize)
        assertEquals(7f, state.fontLineHeight)
        assertEquals(true, state.enableHideStatusBar)
        assertEquals("classic", state.batteryIndicatorDisplayMode)
        assertEquals("FollowSystem", state.darkModeKey)
        assertEquals(UserDataPath.Reader.FontSize.path, state.fontSizeUserData.path)
        assertEquals(UserDataPath.Reader.EnableHideStatusBar.path, state.enableHideStatusBarUserData.path)
        assertEquals(UserDataPath.Settings.Display.DarkMode.path, state.darkModeKeyUserData.path)
    }

    @Test
    fun adapterCanBeNarrowedToReaderAndThemeCapabilities() {
        val state = SettingState(UserDataRepository(InMemoryUserDataDao()), scope)
        val readerSettings: ReaderSettings = state
        val readerEditor: ReaderSettingsEditor = state
        val themeSettings: ThemeSettings = state
        val themeEditor: ThemeSettingsEditor = state

        assertSame(state, readerSettings)
        assertSame(state, readerEditor)
        assertSame(state, themeSettings)
        assertSame(state, themeEditor)
        assertEquals(state.fontSize, readerSettings.fontSize)
        assertEquals(state.darkModeKey, themeSettings.darkModeKey)
    }

    private class InMemoryUserDataDao : UserDataDao {
        private val values = mutableMapOf<String, UserDataEntity>()
        private val flows = mutableMapOf<String, MutableStateFlow<String?>>()

        override suspend fun insert(path: String, group: String, type: String, value: String) {
            values[path] = UserDataEntity(path, group, type, value)
            flows.getOrPut(path) { MutableStateFlow(null) }.value = value
        }

        override suspend fun insert(userDataEntity: UserDataEntity) {
            insert(
                userDataEntity.path,
                userDataEntity.group,
                userDataEntity.type,
                userDataEntity.value
            )
        }

        override suspend fun get(path: String): String? = values[path]?.value

        override fun getFlow(path: String): Flow<String?> =
            flows.getOrPut(path) { MutableStateFlow(values[path]?.value) }

        override fun getEntity(path: String): UserDataEntity? = values[path]

        override fun getGroupValues(group: String): List<UserDataEntity> =
            values.values.filter { it.group == group }

        override suspend fun remove(path: String) {
            values.remove(path)
            flows.getOrPut(path) { MutableStateFlow(null) }.value = null
        }

        override fun getAllEntities(): List<UserDataEntity> = values.values.toList()
    }
}
