package indi.dmzz_yyhyy.lightnovelreader.data.setting

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.StateFactoryMarker
import io.nightfish.lightnovelreader.api.userdata.UserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

abstract class AbstractSettingState(
    private val coroutineScope: CoroutineScope,
) {
    private companion object {
        const val TAG = "AbstractSettingState"
    }

    @StateFactoryMarker
    protected fun <T> UserData<T>.asState(initial: T): State<T> {
        val state = mutableStateOf(initial)
        coroutineScope.launch(Dispatchers.IO) {
            getFlowWithDefault(initial).collect {
                state.value = it
            }
        }
        return state
    }

    @StateFactoryMarker
    protected fun <T> UserData<T>.safeAsState(initial: T): State<T> {
        val state = mutableStateOf(initial)
        coroutineScope.launch(Dispatchers.IO) {
            flow { emitAll(getFlowWithDefault(initial)) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Failed to observe setting $path", error)
                    emit(initial)
                }
                .collect {
                    state.value = it
                }
        }
        return state
    }
}
