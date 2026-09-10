package hnovel.content

import kotlinx.serialization.json.*

data class LoginField(val name: String, val type: String, val action: String? = null)
data class LoginForm(val fields: List<LoginField>, val browserUrl: String?) {
    companion object {
        fun parse(ui: String, loginUrl: String): LoginForm {
            val fields = if (ui.isBlank()) emptyList() else Json.parseToJsonElement(ui).jsonArray.map { value ->
                val row = value.jsonObject
                LoginField(row.getValue("name").jsonPrimitive.content, row["type"]?.jsonPrimitive?.content ?: "text",
                    row["action"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
            }
            require(fields.size <= 32 && fields.all { it.name.isNotBlank() && it.name.length <= 128 && it.type in setOf("text", "password", "button") })
            require(fields.map { it.name }.distinct().size == fields.size)
            val browser = loginUrl.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            return LoginForm(fields, browser)
        }
    }
}
