package indi.renakoni.nextvol.ui.storagemanager

import androidx.annotation.StringRes

data class StorageManagerSection(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val size: Long
)
