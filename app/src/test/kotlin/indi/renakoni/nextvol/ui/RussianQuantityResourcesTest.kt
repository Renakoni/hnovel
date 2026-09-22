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
    @Test fun genericRussianKeepsTranslationsFromBothResourceDirectories() {
        assertRussianNavigationAndActions()
    }

    @Test @Config(qualifiers = "ru-rRU")
    fun regionalRussianKeepsTranslationsFromBothResourceDirectories() {
        assertRussianNavigationAndActions()
    }

    private fun assertRussianNavigationAndActions() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("Чтение", context.getString(R.string.nav_reading))
        assertEquals("Повторить", context.getString(R.string.action_retry))
        assertEquals("Управление плагинами", context.getString(R.string.settings_plugins))
    }

    @Test fun genericRussianVoiceCountsUseOneFewAndMany() {
        assertVoiceCounts()
    }

    @Test @Config(qualifiers = "ru-rRU")
    fun regionalRussianVoiceCountsUseOneFewAndMany() {
        assertVoiceCounts()
    }

    private fun assertVoiceCounts() {
        val resources = RuntimeEnvironment.getApplication().resources
        for ((count, word) in listOf(1 to "голос", 2 to "голоса", 5 to "голосов", 21 to "голос")) {
            assertEquals("$count $word", resources.getQuantityString(R.plurals.tts_voice_count, count, count))
            assertEquals("Импортировать $count $word", resources.getQuantityString(R.plurals.tts_import_count, count, count))
            val entry = when (count) { 1, 21 -> "запись"; 2 -> "записи"; else -> "записей" }
            assertEquals("Не удалось импортировать $count $entry.", resources.getQuantityString(R.plurals.tts_import_invalid, count, count))
        }
        assertEquals("Фрагмент 2 из 5", resources.getString(R.string.tts_passage, 2, 5))
        assertEquals("1,5×", resources.getString(R.string.tts_factor, 1.5))
        assertEquals("Вернуться к книге «Книга»", resources.getString(R.string.tts_return_to_book, "Книга"))
    }

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
