package indi.renakoni.nextvol.ui

import android.app.Application
import indi.renakoni.nextvol.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class, qualifiers = "ru")
class RussianQuantityResourcesTest {
    @Test fun chapterActionsUseRussianPluralRules() {
        val resources = RuntimeEnvironment.getApplication().resources
        for (count in listOf(0, 1, 2, 5, 11, 21, 22, 25, 101, 111)) {
            val ending = when (count) {
                1, 21, 101 -> "главу как прочитанную"
                2, 22 -> "главы как прочитанные"
                else -> "глав как прочитанные"
            }
            assertEquals("Отметить $count $ending", resources.getQuantityString(R.plurals.mark_read_selected, count, count))
        }
    }

    @Test @Config(qualifiers = "ru-rRU")
    fun formattingRuleCountsResolveGenericRussianResourcesForRussia() {
        val resources = RuntimeEnvironment.getApplication().resources
        for ((count, word) in listOf(0 to "правил", 1 to "правило", 2 to "правила", 5 to "правил",
            11 to "правил", 21 to "правило", 22 to "правила", 25 to "правил")) {
            assertEquals("$count $word", resources.getQuantityString(R.plurals.formatting_rules_count, count, count))
        }
    }
}
