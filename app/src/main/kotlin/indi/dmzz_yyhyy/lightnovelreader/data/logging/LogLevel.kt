package indi.dmzz_yyhyy.lightnovelreader.data.logging

sealed class LogLevel(val level: Int) {
    data object NONE : LogLevel(0)
    data object SYSTEM : LogLevel(1)
    data object ERROR : LogLevel(2)
    data object WARNING : LogLevel(4)
    data object INFO : LogLevel(6)
    data object DEBUG : LogLevel(8)
    data object VERBOSE : LogLevel(10)

    companion object {
        fun from(value: String): LogLevel = when (value.lowercase()) {
            "verbose" -> VERBOSE
            "debug" -> DEBUG
            "info" -> INFO
            "warning" -> WARNING
            "error" -> ERROR
            else -> NONE
        }
    }
}