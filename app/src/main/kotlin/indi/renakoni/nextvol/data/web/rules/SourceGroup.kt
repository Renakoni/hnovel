package indi.renakoni.nextvol.data.web.rules

import kotlinx.serialization.Serializable

/** User organization is independent of imported rules and recommendation categories. */
@Serializable
data class SourceGroup(val id: String, val name: String) {
    companion object {
        fun validName(name: String) = name.isNotBlank() && name.length <= 60 && name.none(Char::isISOControl)
    }
}
