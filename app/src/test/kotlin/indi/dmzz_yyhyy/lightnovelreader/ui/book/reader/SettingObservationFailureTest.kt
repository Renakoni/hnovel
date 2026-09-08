package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.setting.AbstractSettingState
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SettingObservationFailureTest {
    @Test
    fun malformedFloatValueUsesTheDefaultAndLaterValidValuesRecoverObservation() = runBlocking {
        val values = MutableStateFlow<String?>("malformed")
        val dao = mockk<UserDataDao> {
            every { getFlow(UserDataPath.Reader.FontSize.path) } returns values
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        try {
            val settings = FontSettings(UserDataRepository(dao), scope)
            assertEquals(15f, settings.fontSize)
            values.value = "22.0"
            withTimeout(5_000) {
                while (settings.fontSize != 22f) delay(1)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun malformedColorValueUsesTheDefaultAndLaterValidValuesRecoverObservation() = runBlocking {
        val values = MutableStateFlow<String?>("malformed")
        val dao = mockk<UserDataDao> {
            every { getFlow(UserDataPath.Reader.TextColor.path) } returns values
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = ColorSettings(UserDataRepository(dao), scope)
            assertEquals(Color.Red, settings.color)
            values.value = Color.Blue.value.toString()
            withTimeout(5_000) {
                while (settings.color != Color.Blue) delay(1)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun storageFailureKeepsTheDefaultWithoutEscapingTheSafeObservation() = runBlocking {
        val dao = mockk<UserDataDao> {
            every { getFlow(UserDataPath.Reader.FontSize.path) } returns flow {
                throw IllegalStateException("storage unavailable")
            }
        }
        var uncaught = false
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> uncaught = true }
        )
        try {
            val settings = FontSettings(UserDataRepository(dao), scope)
            assertEquals(15f, settings.fontSize)
            assertTrue(!uncaught)
        } finally {
            scope.cancel()
        }
    }

    private class FontSettings(repository: UserDataRepository, scope: CoroutineScope) : AbstractSettingState(scope) {
        val fontSize by repository.floatUserData(UserDataPath.Reader.FontSize.path).safeAsState(15f)
    }

    private class ColorSettings(repository: UserDataRepository, scope: CoroutineScope) : AbstractSettingState(scope) {
        val color by repository.colorUserData(UserDataPath.Reader.TextColor.path).safeAsState(Color.Red)
    }
}
