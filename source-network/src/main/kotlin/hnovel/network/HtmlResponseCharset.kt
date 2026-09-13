package hnovel.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.jsoup.Jsoup
import java.nio.charset.Charset

/** Inspect only a bounded HTML prefix; never parse the full response or rewrite its bytes. */
internal fun htmlResponseCharset(bytes: ByteArray, contentType: MediaType?): String? {
    if (contentType != null && !(contentType.type == "text" && contentType.subtype == "html") &&
        !(contentType.type == "application" && contentType.subtype == "xhtml+xml")) return null
    val prefix = String(bytes, 0, minOf(bytes.size, 8192), Charsets.ISO_8859_1)
    for (meta in Jsoup.parse(prefix).head().children()) {
        if (meta.normalName() != "meta") continue
        val name = if (meta.hasAttr("charset")) meta.attr("charset").trim()
            else if (meta.attr("http-equiv").trim().equals("content-type", true))
                meta.attr("content").toMediaTypeOrNull()?.charset()?.name()
            else null
        if (name.isNullOrBlank()) continue
        try { return Charset.forName(name).name() }
        catch (_: IllegalArgumentException) { /* Ignore unsupported declarations and retain the normal fallback. */ }
    }
    return null
}
