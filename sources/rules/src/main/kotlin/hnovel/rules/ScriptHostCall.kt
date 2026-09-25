package hnovel.rules

import kotlinx.serialization.Serializable

/** A registered host operation and bounded argument shapes, never names or values from source data. */
@Serializable data class ScriptHostCall(val method: String, val argumentCount: Int,
    val argumentTypes: List<ScriptArgumentType>) {
    init { require(method.length <= 64 && argumentCount >= argumentTypes.size && argumentTypes.size <= 8) }
}

@Serializable enum class ScriptArgumentType { Null, Undefined, Boolean, Number, String, Array, Object, Function, Unknown }
