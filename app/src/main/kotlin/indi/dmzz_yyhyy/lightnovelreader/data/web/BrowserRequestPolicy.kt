package indi.dmzz_yyhyy.lightnovelreader.data.web

import java.net.URI

enum class BrowserRequestDecision { Allow, Deny }

/** Applies the same host boundary to navigation and subresource requests. */
class BrowserRequestPolicy(allowedHosts: Set<String>) {
    private val hosts = allowedHosts.map { it.lowercase().trimEnd('.') }.toSet()

    fun decide(url: String): BrowserRequestDecision {
        val uri = try { URI(url) } catch (_: Exception) { return BrowserRequestDecision.Deny }
        val scheme = uri.scheme?.lowercase() ?: return BrowserRequestDecision.Deny
        if (scheme !in setOf("http", "https")) return BrowserRequestDecision.Deny
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return BrowserRequestDecision.Deny
        return if (host in hosts) BrowserRequestDecision.Allow else BrowserRequestDecision.Deny
    }
}
