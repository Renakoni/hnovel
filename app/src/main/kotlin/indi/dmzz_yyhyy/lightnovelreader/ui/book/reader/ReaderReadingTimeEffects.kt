package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
) {
    var isRunning by remember { mutableStateOf(false) }
    var totalReadingTime by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        isRunning = true
        onPauseOrDispose {
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
            totalReadingTime += 1
            if (totalReadingTime > 60) {
                currentBookId()?.let {
                    updateTotalReadingTime(it, totalReadingTime)
                }
                totalReadingTime = 0
            }
            delay(1.seconds)
        }
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            currentBookId()?.let {
                accumulateReadTime(it, 1)
            }
            delay(1.seconds)
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
