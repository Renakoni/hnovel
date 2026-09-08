package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import io.nightfish.lightnovelreader.api.userdata.BooleanUserData
import kotlinx.coroutines.flow.Flow

/** The scroll controller needs both observation and the stored value at chapter-request time. */
interface ContinuousScrollSettings {
    fun getFlow(): Flow<Boolean>
    suspend fun isEnabled(): Boolean
}

internal class UserDataContinuousScrollSettings(
    private val userData: BooleanUserData,
) : ContinuousScrollSettings {
    override fun getFlow(): Flow<Boolean> = userData.getFlowWithDefault(true)
    override suspend fun isEnabled(): Boolean = userData.getOrDefault(true)
}
