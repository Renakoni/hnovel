package android.text

// The sole Android text operation used by the unmodified reference selectors.
object TextUtils {
    @JvmStatic
    fun join(delimiter: CharSequence, tokens: Iterable<*>): String =
        tokens.joinToString(delimiter.toString())
}
