package indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class JsonObjectConverter {
    @TypeConverter
    fun jsonObjectToString(jsonObject: JsonObject) = jsonObject.toString()

    @TypeConverter
    fun stringToJsonObject(string: String): JsonObject = Json.parseToJsonElement(string).jsonObject
}