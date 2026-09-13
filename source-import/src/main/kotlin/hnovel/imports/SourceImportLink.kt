package hnovel.imports

import hnovel.network.sourceOrigin
import java.net.URI
import java.net.URLDecoder

/** Accept the same book-source links published by Legado collections, without importing other formats. */
fun sourceImportUrl(input: String): String? = runCatching {
    val text = input.trim()
    if (text.length > 32768) return null
    if (sourceOrigin(text) != null) return text
    val link = URI(text)
    if (link.scheme != "legado" || link.host != "import" || link.path != "/bookSource" || link.rawFragment != null) return null
    val values = link.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
        .filter { it.size == 2 && it[0] == "src" }
    if (values.size != 1) return null
    URLDecoder.decode(values.single()[1], "UTF-8").takeIf { sourceOrigin(it) != null }
}.getOrNull()
