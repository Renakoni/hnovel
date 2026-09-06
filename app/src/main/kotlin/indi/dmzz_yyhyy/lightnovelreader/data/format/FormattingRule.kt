package indi.dmzz_yyhyy.lightnovelreader.data.format

data class FormattingRule(
    val id: Int? = null,
    val name: String,
    val isRegex: Boolean,
    val match: String,
    val replacement: String,
    val isEnabled: Boolean
)