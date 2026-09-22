package hnovel.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** Recognize verification documents before a successful HTTP status can become an empty book list. */
internal fun websiteChallenge(response: BrokerResponse): BrowserChallengeKind? {
    if (response.headers.any { (name, values) -> name.equals("cf-mitigated", true) && values.any { it == "challenge" } })
        return BrowserChallengeKind.Cloudflare
    val prefix = response.body.copyOfRange(0, minOf(response.body.size, 131072))
        .toString(java.nio.charset.Charset.forName(response.charset)).trimStart { it.isWhitespace() || it == '\uFEFF' }
    if (!prefix.startsWith('<')) return null
    val document = Jsoup.parse(prefix)
    val title = document.title().trim()
    val path = response.finalUrl.toHttpUrlOrNull()?.encodedPath.orEmpty()
    return when {
        title.startsWith("Just a moment", true) &&
            (prefix.contains("_cf_chl_opt") || prefix.contains("/cdn-cgi/challenge-platform/")) -> BrowserChallengeKind.Cloudflare
        path.startsWith("/WAF/VERIFY/CAPTCHA", true) &&
            (title.equals("Verify Yourself", true) || document.selectFirst("form#ui-form") != null) -> BrowserChallengeKind.SiteVerification
        (path == "/antibot" || path.startsWith("/antibot/")) || title.startsWith("人机校验") ||
            document.selectFirst("form#J_ManMachineVerify") != null -> BrowserChallengeKind.SiteVerification
        (path == "/login" || path.startsWith("/login/")) && document.selectFirst("input[type=password]") != null -> BrowserChallengeKind.Login
        else -> null
    }
}
