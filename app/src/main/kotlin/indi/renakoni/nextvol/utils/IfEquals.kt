package indi.renakoni.nextvol.utils

fun <C, R> C.ifEquals(value: String, block: () -> R): R where C : R {
    return if (this == value) block.invoke()
    else this
}