package com.maxrave.domain.utils

/**
 * Force HTTPS when the user enabled Settings → HTTPS-only.
 *
 * Upgrades known media hosts that still advertise http:// in fallbacks. Unknown hosts are left
 * alone so a local LAN stream still loads; a global rewrite would break self-signed intranet URLs.
 */
fun String.enforceHttpsIf(httpsOnly: Boolean): String {
    if (!httpsOnly) return this
    if (!startsWith("http://", ignoreCase = true)) return this
    val host = substringAfter("://").substringBefore('/').lowercase()
    val upgrade =
        host == "i.ytimg.com" ||
            host == "i9.ytimg.com" ||
            host.endsWith(".ytimg.com") ||
            host == "suggestqueries.google.com" ||
            host == "music.youtube.com"
    return if (upgrade) "https" + removePrefix("http") else this
}
