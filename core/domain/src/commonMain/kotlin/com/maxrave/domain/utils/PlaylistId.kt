package com.maxrave.domain.utils

private val RADIO_PLAYLIST_ID_PREFIXES = listOf("RDAMVM", "RDEM", "RDAT", "RDTM")

private val RADIO_MIX_ID_PREFIXES = listOf("RDAT", "RDTM")

// Anything paging through such a playlist must stop on its own terms — waiting for YouTube to run out of continuations never happens.
fun String.isRadioPlaylistId(): Boolean = RADIO_PLAYLIST_ID_PREFIXES.any { startsWith(it) }

fun String.isRadioQueueId(): Boolean = removePrefix("VL").startsWith("RD")

fun String.isRadioMix(): Boolean = RADIO_MIX_ID_PREFIXES.any { startsWith(it) }