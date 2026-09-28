package indi.renakoni.nextvol.data.book

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

internal val UNKNOWN_BOOK_UPDATE_TIME: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)

private val wordCountPattern = Regex("""([0-9]+(?:[.][0-9]+)?|[0-9]{1,3}(?:[,，][0-9]{3})+(?:[.][0-9]+)?)\s*([万亿千kKwW]?)""")

internal fun parseBookWordCount(value: String): Int? {
    val match = wordCountPattern.matchEntire(value.trim().removeSuffix("字").trim()) ?: return null
    val multiplier = when (match.groupValues[2].lowercase()) {
        "千", "k" -> 1_000
        "万", "w" -> 10_000
        "亿" -> 100_000_000
        else -> 1
    }
    return try {
        match.groupValues[1].replace(",", "").replace("，", "").toBigDecimal()
            .multiply(multiplier.toBigDecimal()).intValueExact().takeIf { it > 0 }
    } catch (_: ArithmeticException) {
        null
    }
}

private val bookDateTimeFormats = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ofPattern("uuuu-M-d H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT),
    DateTimeFormatter.ofPattern("uuuu/M/d H:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)
)
private val bookDateFormats = listOf(
    DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT),
    DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT),
    DateTimeFormatter.ofPattern("uuuu年M月d日").withResolverStyle(ResolverStyle.STRICT)
)

/** Preserve the site's calendar date; observation time is not a source update date. */
internal fun parseBookUpdateTime(value: String): LocalDateTime? {
    val text = value.trim()
    if (text.isEmpty()) return null
    try {
        return OffsetDateTime.parse(text).toLocalDateTime().takeIf { it.year > 1970 }
    } catch (_: DateTimeParseException) {
        // Sources also return local timestamps and dates without an offset.
    }
    for (format in bookDateTimeFormats) try {
        return LocalDateTime.parse(text, format).takeIf { it.year > 1970 }
    } catch (_: DateTimeParseException) {
        continue
    }
    for (format in bookDateFormats) try {
        return LocalDate.parse(text, format).atStartOfDay().takeIf { it.year > 1970 }
    } catch (_: DateTimeParseException) {
        continue
    }
    return null
}
