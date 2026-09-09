package com.maxrave.domain.lyrics

private val RICH_SYNC_TIMESTAMP_REGEX = Regex("""<\d{2}:\d{2}\.\d{2,3}>\s*""")
private val WHITESPACE_REGEX = Regex("""\s+""")

/**
 * The words of a line, with word-by-word timing markers removed.
 *
 * `RICH_SYNCED` lyrics carry a timestamp before every word. Each is replaced with a space rather
 * than nothing, then runs of whitespace are collapsed — dropping them outright runs the words
 * together.
 *
 * Lives in the domain because two very different renderers need the same plain text: the player's
 * lyrics views, and the Android Auto overlay, which has one row to put a line in and no way to
 * show timing markers as anything but noise.
 */
fun stripRichSyncTimestamps(words: String): String =
    words
        .replace(RICH_SYNC_TIMESTAMP_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
