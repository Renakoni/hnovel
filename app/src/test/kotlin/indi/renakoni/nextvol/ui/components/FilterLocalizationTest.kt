package indi.renakoni.nextvol.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.explore.filter.IsCompletedSwitchFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.SliderFilter
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

    @Test fun commonFiltersUseEnglishAndLegacyPluginTextIsPreserved() =
        assertFilterText("Word count: No limit", "Completed")

    @Test @Config(qualifiers = "zh-rCN")
    fun commonFiltersUseChineseAndLegacyPluginTextIsPreserved() =
        assertFilterText("字数: 无限制", "已完结")

    private fun assertFilterText(wordCount: String, completed: String) {
        val builtIn = WordCountFilter()
        val completedFilter = IsCompletedSwitchFilter()
        val legacy = object : SliderFilter("Plugin filter".local(), "Plugin description", 0f, 0f..10f) {
            override var enabled = true
            override val displayValue get() = "Plugin value: ${value.toInt()}"
        }
        legacy.value = 4f
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    Column {
                        builtIn.Component(dialog = {}, onChange = {})
                        completedFilter.Component(dialog = {}, onChange = {})
                        legacy.Component(dialog = {}, onChange = {})
                        Text(legacy.getDescriptionText().resolve())
                    }
                }
            }
        }
        compose.onNodeWithText(wordCount).assertIsDisplayed()
        compose.onNodeWithText(completed).assertIsDisplayed()
        compose.onNodeWithText("Plugin description").assertIsDisplayed()
        compose.onNodeWithText("Plugin filter: Plugin value: 4").assertIsDisplayed()
    }
}
