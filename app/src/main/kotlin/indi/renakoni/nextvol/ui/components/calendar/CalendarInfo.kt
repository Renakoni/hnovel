package indi.renakoni.nextvol.ui.components.calendar

import androidx.compose.runtime.Immutable
import indi.renakoni.nextvol.ui.components.calendar.core.OutDateStyle
import java.time.DayOfWeek

@Immutable
internal data class CalendarInfo(
    val indexCount: Int,
    private val firstDayOfWeek: DayOfWeek? = null,
    private val outDateStyle: OutDateStyle? = null,
)