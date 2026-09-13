package hnovel.rules

import kotlinx.serialization.Serializable

/** Fixed unavailable runtime bindings, never identifiers or error messages supplied by a source. */
@Serializable
enum class ScriptDependency(val binding: String) {
    JavaImporter("JavaImporter"), Packages("Packages"), ImportClass("importClass"),
    ImportPackage("importPackage"), JavaAdapter("JavaAdapter")
}
