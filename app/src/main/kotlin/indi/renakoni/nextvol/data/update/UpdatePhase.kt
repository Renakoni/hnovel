package indi.renakoni.nextvol.data.update

import androidx.annotation.StringRes

/** Display resources are resolved by the current UI, not cached in the update worker's locale. */
data class UpdatePhase(
    @param:StringRes val messageId: Int,
    val arguments: List<Any> = emptyList(),
)
