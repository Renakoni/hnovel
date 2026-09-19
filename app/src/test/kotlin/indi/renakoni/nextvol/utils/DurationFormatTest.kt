package indi.renakoni.nextvol.utils

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class DurationFormatTest {
    @Test fun englishUnitsAndZeroRespectTheRequestedPrecision() {
        val format = DurationFormat(Locale.US)
        assertEquals("1h 2m", format.format(1.hours + 2.minutes + 30.seconds, DurationFormat.Unit.MINUTE))
        assertEquals("62m", format.format(1.hours + 2.minutes, DurationFormat.Unit.MINUTE, DurationFormat.Unit.MINUTE))
        assertEquals("0m", format.format(30.seconds, DurationFormat.Unit.MINUTE))
    }

    @Test fun chineseUnitsFollowTheExplicitLocaleEvenWhenTheDefaultIsEnglish() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)) {
                val result = DurationFormat(locale).format(1.hours + 2.minutes, DurationFormat.Unit.MINUTE)
                assertTrue(result, result.contains("1") && result.contains("2"))
                assertTrue(result, Regex("[\\p{IsHan}]").containsMatchIn(result))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
