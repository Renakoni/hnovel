package indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter

import android.net.Uri
import androidx.core.net.toUri
import androidx.room.TypeConverter

object UriConverter {

    @TypeConverter
    fun uriToString(uri: Uri) = uri.toString()

    @TypeConverter
    fun stringToUri(string: String) = string.toUri()
}