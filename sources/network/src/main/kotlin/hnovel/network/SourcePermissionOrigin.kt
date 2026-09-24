package hnovel.network

import java.net.URI

/** Shared validation for suggested and manually approved websites. Does not grant access. */
fun sourcePermissionOrigin(address: String): String? = runCatching {
    val input = URI(address.trim())
    if (input.scheme?.lowercase() !in setOf("http", "https") || input.rawAuthority == null ||
        '@' in input.rawAuthority) return null
    val origin = sourceOrigin(address.trim()) ?: return null
    // Validate the canonical host, including IDN/IPv6, without accepting partial numeric IPs.
    origin.takeIf { URI(it).host != null }
}.getOrNull()
