package indi.renakoni.nextvol.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.nightfish.lightnovelreader.api.web.explore.filter.IsCompletedSwitchFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.WordCountFilter
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FilterLocalizationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun setUp() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun tearDown() { activity.pause().stop().destroy() }

    @Test fun commonFiltersUseEnglish() =
        assertFilterText("Word count: No limit", "Completed")

    @Test @Config(qualifiers = "zh-rCN")
    fun commonFiltersUseChinese() =
        assertFilterText("字数: 无限制", "已完结")

    @Test @Config(qualifiers = "ru")
    fun commonFiltersUseRussian() =
        assertFilterText("Число слов: Без ограничений", "Завершено")

    private fun assertFilterText(wordCount: String, completed: String) {
        val builtIn = WordCountFilter()
        val completedFilter = IsCompletedSwitchFilter()
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    Column {
                        builtIn.Component(dialog = {}, onChange = {})
                        completedFilter.Component(dialog = {}, onChange = {})
                    }
                }
            }
        }
        compose.onNodeWithText(wordCount).assertIsDisplayed()
        compose.onNodeWithText(completed).assertIsDisplayed()
    }
}
