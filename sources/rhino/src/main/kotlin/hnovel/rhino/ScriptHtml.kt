// SPDX-License-Identifier: GPL-3.0-only
// Adapted from hectorqin/legado@da17bb2bed44f30b12a524c2457e32a20b16fa41; see THIRD_PARTY.md.
package hnovel.rhino

/** JsExtensions.htmlFormat uses HtmlFormatter.formatKeepImg without a redirect URL. */
internal object ScriptHtml {
    // Android's ICU regex whitespace is Unicode; spell it out for the desktop worker too.
    private const val space = "[\\s\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]"
    private val replacements = listOf(
        Regex("(&nbsp;)+") to " ",
        Regex("(&ensp;|&emsp;)") to " ",
        Regex("(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)") to "",
        Regex("</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>") to "\n",
        Regex("<!--[^>]*-->") to "",
        Regex("</?(?!img)[a-zA-Z]+(?=[ >])[^<>]*>") to "",
        Regex("$space*\\n+$space*") to "\n\u3000\u3000",
        Regex("^$space+") to "\u3000\u3000",
        Regex("$space+$") to "",
    )
    private val image = Regex("<img[^>]*\\ssrc\\s*=\\s*['\"]([^'\"{>]*\\{(?:[^{}]|\\{[^}>]+\\})+\\})['\"][^>]*>|<img[^>]*\\s(?:data-src|src)\\s*=\\s*['\"]([^'\">]+)['\"][^>]*>|<img[^>]*\\sdata-[^=>]*=\\s*['\"]([^'\">]*)['\"][^>]*>", RegexOption.IGNORE_CASE)

    fun format(html: String): String {
        val formatted = replacements.fold(html) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
        return image.replace(formatted) { match ->
            val source = (match.groups[1] ?: match.groups[2] ?: match.groups[3])!!.value.trim()
            "<img src=\"$source\">"
        }
    }
}
