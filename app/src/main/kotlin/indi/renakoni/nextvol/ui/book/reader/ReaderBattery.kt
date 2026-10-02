package indi.renakoni.nextvol.ui.book.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

@Composable
internal fun rememberReaderBatteryLevel(): State<Int?> {
    val context = LocalContext.current.applicationContext
    val levels = remember(context) {
        callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    trySend(intent.batteryPercentage())
                }
            }
            ContextCompat.registerReceiver(context, receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            awaitClose { context.unregisterReceiver(receiver) }
        }.conflate().distinctUntilChanged().flowOn(Dispatchers.IO)
    }
    // The sticky broadcast refreshes the value on each STARTED subscription. Registration and
    // cleanup are Binder calls too, so they run on IO, never in composition or on the frame thread.
    return levels.collectAsStateWithLifecycle(initialValue = null)
}

private fun Intent.batteryPercentage(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (scale > 0 && level in 0..scale) (level.toLong() * 100 / scale).toInt() else null
}
