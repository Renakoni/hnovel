package indi.dmzz_yyhyy.lightnovelreader.utils

import android.icu.text.DateTimePatternGenerator
import android.icu.util.ULocale
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions.DateFormatOptions
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions.DateOrderOptions
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 应用内日期的显示格式
 *
 *  - NUMERIC → 2025/12/31
 *  - WRITTEN → 2025年12月31日, December 31, 2025
 */
enum class DateFormat {
    NUMERIC, WRITTEN;

    companion object {
        fun fromString(value: String, default: DateFormat = NUMERIC): DateFormat =
            when (value.lowercase()) {
                DateFormatOptions.Numeric -> NUMERIC
                DateFormatOptions.Written -> WRITTEN
                else -> default
            }
    }
}

val appDisplayLocale: Locale
    get() = Locale.getDefault()

enum class DateOrder {
    AUTO, YMD, DMY, MDY;

    companion object {
        fun fromString(value: String): DateOrder = when (value.lowercase()) {
            DateOrderOptions.YMD -> YMD
            DateOrderOptions.DMY -> DMY
            DateOrderOptions.MDY -> MDY
            else -> AUTO
        }
    }
}

fun dateFormatter(
    format: DateFormat,
    showYear: Boolean = true,
    order: DateOrder = DateOrder.AUTO
): DateTimeFormatter {
    val locale = appDisplayLocale

    return when (format) {
        DateFormat.NUMERIC -> {
            if (order == DateOrder.AUTO) {
                val generator = DateTimePatternGenerator.getInstance(
                    ULocale.forLocale(locale)
                )
                val pattern = if (showYear) "yMd" else "Md"
                DateTimeFormatter.ofPattern(generator.getBestPattern(pattern), locale)
            } else {
                DateTimeFormatter.ofPattern(
                    when {
                        showYear && order == DateOrder.DMY -> "d/M/yyyy"
                        showYear && order == DateOrder.MDY -> "M/d/yyyy"
                        showYear && order == DateOrder.YMD -> "yyyy/M/d"
                        !showYear && order == DateOrder.DMY -> "d/M"
                        else -> "M/d"
                    },
                    locale
                )
            }
        }

        DateFormat.WRITTEN -> {
            if (showYear) {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
            } else {
                val generator = DateTimePatternGenerator.getInstance(
                    ULocale.forLocale(locale)
                )
                DateTimeFormatter.ofPattern(generator.getBestPattern("MMMMd"), locale)
            }
        }
    }
}

fun dateFormatter(): DateTimeFormatter = dateFormatter(
    DateFormat.fromString(FormattingSettings.dateFormat),
    FormattingSettings.dateShowYear,
    DateOrder.fromString(FormattingSettings.dateOrder)
)
