package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.app.Application
import androidx.compose.runtime.getValue
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.setting.AbstractSettingState
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
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
    fun malformedValueTerminatesSafeObservationAndLaterValidValuesDoNotRecoverIt() = runBlocking {
        val values = MutableStateFlow<String?>("malformed")
        val dao = mockk<UserDataDao> {
            every { getFlow(UserDataPath.Reader.FontSize.path) } returns values
        }
        val failure = CompletableDeferred<Throwable>()
        val job = SupervisorJob()
        // Capture the otherwise uncaught exception without changing production collection behavior.
        val scope = CoroutineScope(job + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error ->
            failure.complete(error)
        })
        try {
            val settings = FontSettings(UserDataRepository(dao), scope)
            assertTrue(withTimeout(5_000) { failure.await() } is NumberFormatException)
            withTimeout(5_000) { job.children.toList().joinAll() }
            assertEquals(0, values.subscriptionCount.value)
            values.value = "22.0"
            assertEquals(15f, settings.fontSize)
            assertEquals(0, values.subscriptionCount.value)
        } finally {
            scope.cancel()
        }
    }

    private class FontSettings(repository: UserDataRepository, scope: CoroutineScope) : AbstractSettingState(scope) {
        val fontSize by repository.floatUserData(UserDataPath.Reader.FontSize.path).safeAsState(15f)
    }
}
