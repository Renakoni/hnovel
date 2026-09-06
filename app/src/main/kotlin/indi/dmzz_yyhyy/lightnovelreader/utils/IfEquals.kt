package indi.dmzz_yyhyy.lightnovelreader.utils

fun <C, R> C.ifEquals(value: String, block: () -> R): R where C : R {
    return if (this == value) block.invoke()
    else this
}