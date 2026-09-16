package indi.renakoni.nextvol.data.local.room.converter

import androidx.room.TypeConverter
import indi.renakoni.nextvol.data.statistics.Count

class CountConverter {
    @TypeConverter
    fun fromCount(count: Count?): ByteArray? = count?.toByteArray()

    @TypeConverter
    fun toCount(bytes: ByteArray?): Count? = bytes?.let { Count.fromByteArray(it) }
}