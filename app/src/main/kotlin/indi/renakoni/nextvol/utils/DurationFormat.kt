package indi.renakoni.nextvol.utils

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class DurationFormat(val locale: Locale = Locale.getDefault()) {
    enum class Unit {
        DAY, HOUR, MINUTE, SECOND, MILLISECOND
    }

    fun format(duration: Duration, smallestUnit: Unit = Unit.SECOND, largestUnit: Unit = Unit.DAY): String {
        val formattedStringComponents = mutableListOf<String>()
        val measureFormat = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
        var remainder = duration

        for (unit in Unit.entries) {
            if (unit.ordinal < largestUnit.ordinal) continue

            val component = calculateComponent(unit, remainder)

            remainder = when (unit) {
                Unit.DAY -> remainder - component.days
                Unit.HOUR -> remainder - component.hours
                Unit.MINUTE -> remainder - component.minutes
                Unit.SECOND -> remainder - component.seconds
                Unit.MILLISECOND -> remainder - component.milliseconds
            }

            val measureUnit = measureUnit(unit)

            if (component > 0) {
                formattedStringComponents.add(measureFormat.format(Measure(component, measureUnit)))
            }

            if (unit == smallestUnit) {
                if (formattedStringComponents.isEmpty()) {
                    formattedStringComponents.add(measureFormat.format(Measure(0, measureUnit)))
                }
                break
            }
        }

        return formattedStringComponents.joinToString(" ")
    }

    private fun calculateComponent(unit: Unit, remainder: Duration) = when (unit) {
        Unit.DAY -> remainder.inWholeDays
        Unit.HOUR -> remainder.inWholeHours
        Unit.MINUTE -> remainder.inWholeMinutes
        Unit.SECOND -> remainder.inWholeSeconds
        Unit.MILLISECOND -> remainder.inWholeMilliseconds
    }

    private fun measureUnit(unit: Unit) = when (unit) {
        Unit.DAY -> MeasureUnit.DAY
        Unit.HOUR -> MeasureUnit.HOUR
        Unit.MINUTE -> MeasureUnit.MINUTE
        Unit.SECOND -> MeasureUnit.SECOND
        Unit.MILLISECOND -> MeasureUnit.MILLISECOND
    }
}
