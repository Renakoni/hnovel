package indi.renakoni.nextvol.defaultplugin.wenku8.search

import indi.renakoni.nextvol.data.text.SimplifiedTraditionalProcessor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer
import java.util.Locale

internal object Wenku8SearchText {
    fun query(text: String): String = SimplifiedTraditionalProcessor.toSimplified(
        Normalizer.normalize(text, Normalizer.Form.NFKC)).trim()

    fun key(text: String): String = query(text).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun names(title: String): List<String> = (listOf(title, title.substringBefore('(').substringBefore('（')) +
        Regex("[（(]([^()（）]+)[)）]").findAll(title).map { it.groupValues[1] }).filter(String::isNotBlank).distinct()

    fun explicitId(text: String): String? {
        val input = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        Regex("#[1-9][0-9]*").matchEntire(input)?.let { return it.value.drop(1).toIntOrNull()?.toString() }
        val url = input.toHttpUrlOrNull() ?: return null
        if (url.host !in setOf("www.wenku8.cc", "www.wenku8.net", "www.wenku8.com", "wenku8.cc", "wenku8.net", "wenku8.com") ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val id = Regex("^/book/([1-9][0-9]*)\\.htm$").matchEntire(url.encodedPath)?.groupValues?.get(1)
            ?: Regex("^/novel/[0-9]+/([1-9][0-9]*)/index\\.htm$").matchEntire(url.encodedPath)?.groupValues?.get(1)
        return id?.toIntOrNull()?.toString()
    }

    fun score(query: String, name: String): Int? = when {
        query.isEmpty() || name.isEmpty() -> null
        name == query -> 0
        name.startsWith(query) -> 10
        query in name -> 20
        query.length >= 4 && oneEdit(query, name) -> 40
        else -> null
    }

    private fun oneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            if (a.length >= b.length) i++
            if (b.length >= a.length) j++
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
