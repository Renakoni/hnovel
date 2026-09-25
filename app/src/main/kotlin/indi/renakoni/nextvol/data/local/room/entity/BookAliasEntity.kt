package indi.renakoni.nextvol.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "book_alias")
data class BookAliasEntity(@PrimaryKey val id: String, val canonicalId: String)
