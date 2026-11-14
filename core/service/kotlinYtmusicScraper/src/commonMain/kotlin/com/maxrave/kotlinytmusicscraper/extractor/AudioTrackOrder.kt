package com.maxrave.kotlinytmusicscraper.extractor

private val XTAGS = Regex("[?&]xtags=([^&]+)")
private val CLEN = Regex("[?&]clen=(\\d+)")

internal fun List<Pair<Int, String>>.orderByAudioTrack(preferredLanguage: String?): List<Pair<Int, String>> =
    sortedBy { (_, url) -> audioTrackRank(url, preferredLanguage) }

internal fun audioTrackRank(
    url: String,
    preferredLanguage: String?,
): Int {
    val tags =
        XTAGS
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.replace("%3D", "=", ignoreCase = true)
            ?.replace("%3A", ":", ignoreCase = true)
            ?.split(':')
            ?.associate { it.substringBefore('=') to it.substringAfter('=', "") }
            .orEmpty()
    val acont = tags["acont"]
    val lang = tags["lang"]?.substringBefore('-')
    val rank =
        when {
            // "descriptive" is narration for blind viewers — never what a language preference means.
            !preferredLanguage.isNullOrBlank() &&
                acont != "descriptive" &&
                lang.equals(preferredLanguage.substringBefore('-'), ignoreCase = true) -> 0
            acont == null || acont == "original" -> 1
            else -> 2
        }
    return rank * 2 + if (tags["drc"] == "1") 1 else 0
}

internal fun contentLengthOf(url: String): Long? = CLEN.find(url)?.groupValues?.get(1)?.toLongOrNull()
