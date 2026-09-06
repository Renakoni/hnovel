package indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter

import androidx.room.TypeConverter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@OptIn(ExperimentalSerializationApi::class)
object ChapterReadingProgressMapConverter {
    @TypeConverter
    fun mapToByteArray(map: Map<String, Float>): ByteArray {
        return Cbor.encodeToByteArray(map)
    }

    @TypeConverter
    fun byteArrayToMap(byteArray: ByteArray): Map<String, Float> {
        return Cbor.decodeFromByteArray(byteArray)
    }
}