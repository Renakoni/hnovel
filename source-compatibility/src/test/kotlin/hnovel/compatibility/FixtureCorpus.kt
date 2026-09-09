package hnovel.compatibility

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object FixtureCorpus {
    val gson = GsonBuilder().setPrettyPrinting().create()

    fun text(path: String): String = requireNotNull(javaClass.getResourceAsStream("/$path")) {
        "Missing fixture: $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun json(path: String): JsonObject = JsonParser.parseString(text(path)).asJsonObject

    fun cases(): List<JsonObject> = json("cases.json").getAsJsonArray("cases").map { it.asJsonObject }

    fun assertOutput(id: String, expected: JsonElement, actual: JsonElement) {
        if (expected != actual) {
            throw AssertionError("$id\nExpected: ${gson.toJson(expected)}\nActual: ${gson.toJson(actual)}")
        }
    }
}

internal fun JsonObject.string(key: String): String = get(key).asString
