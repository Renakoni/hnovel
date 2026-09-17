package indi.renakoni.nextvol.data.localbook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface LocalBookBlock {
    @Serializable @SerialName("text") data class Text(val value: String) : LocalBookBlock
    @Serializable @SerialName("image") data class Image(val path: String) : LocalBookBlock
}

@Serializable
data class LocalBookChapter(
    val title: String,
    val volume: String = "",
    val blocks: List<LocalBookBlock>,
)

data class ParsedLocalBook(
    val title: String,
    val chapters: List<LocalBookChapter>,
    val author: String = "",
    val description: String = "",
    val publishingHouse: String = "",
    val coverPath: String? = null,
    val encoding: String? = null,
)
