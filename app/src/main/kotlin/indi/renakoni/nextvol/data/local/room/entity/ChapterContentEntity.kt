package indi.renakoni.nextvol.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import indi.renakoni.nextvol.data.local.room.converter.JsonObjectConverter
import indi.renakoni.nextvol.data.serializer.JsonObjectSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@TypeConverters(
    JsonObjectConverter::class
)
@Entity(tableName = "chapter_content")
data class ChapterContentEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @Serializable(JsonObjectSerializer::class)
    val content: JsonObject,
    @ColumnInfo(name = "lastChapter")
    val prevChapter: String,
    val nextChapter: String
): Mergeable<ChapterContentEntity> {
    override fun merge(new: ChapterContentEntity): ChapterContentEntity = new
}
