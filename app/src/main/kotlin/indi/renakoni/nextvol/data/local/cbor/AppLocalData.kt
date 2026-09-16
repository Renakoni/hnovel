package indi.renakoni.nextvol.data.local.cbor

import kotlinx.serialization.Serializable

@Serializable
data class AppLocalData(
    val version: Int = 1,
    val localDataList: List<LocalData>,
    val globalLocalData: LocalData
)