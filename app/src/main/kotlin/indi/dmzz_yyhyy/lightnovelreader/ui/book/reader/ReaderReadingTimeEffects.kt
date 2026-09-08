package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun ReaderReadingTimeEffects(
    currentBookId: () -> String?,
    updateTotalReadingTime: (bookId: String, Int) -> Unit,
    accumulateReadTime: (bookId: String, Int) -> Unit,
    nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    var isRunning by remember { mutableStateOf(false) }
    var totalReadingTime by remember { mutableIntStateOf(0) }
    var lastMeasuredAtMillis by remember { mutableLongStateOf(0L) }

    fun recordElapsedTime() {
        val now = nowMillis()
        val elapsedMillis = (now - lastMeasuredAtMillis).coerceAtLeast(0L)
        val elapsedSeconds = elapsedMillis / 1_000L
        if (elapsedSeconds == 0L) return
        lastMeasuredAtMillis += elapsedSeconds * 1_000L

        val bookId = currentBookId() ?: return
        val seconds = elapsedSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        accumulateReadTime(bookId, seconds)
        totalReadingTime = (totalReadingTime.toLong() + elapsedSeconds)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        while (totalReadingTime >= 60) {
            updateTotalReadingTime(bookId, 60)
            totalReadingTime -= 60
        }
    }

    LifecycleResumeEffect(Unit) {
        lastMeasuredAtMillis = nowMillis()
        isRunning = true
        onPauseOrDispose {
            recordElapsedTime()
            isRunning = false
            if (totalReadingTime <= 60) {
                currentBookId()?.let {
                    updateTotalReadingTime(it, totalReadingTime)
                }
            } else {
                Log.e("ReaderScreen", "time counter error, time now is $totalReadingTime over 60s")
            }
            totalReadingTime = 0
        }
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            delay(1.seconds)
            if (isRunning) recordElapsedTime()
        }
    }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            currentBookId()?.let {
                accumulateReadTime(it, -1)
            }
        }
    }

}
