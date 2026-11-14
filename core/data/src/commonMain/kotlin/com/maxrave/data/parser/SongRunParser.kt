package com.maxrave.data.parser

import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import com.maxrave.kotlinytmusicscraper.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.Run

internal enum class SongRunType {
    ALBUM,
    ARTIST,
    DURATION,
    YEAR,
    VIEWS,
}

private val DURATION_REGEX = Regex("^(\\d+:)*\\d+:\\d+$")

private val YEAR_REGEX = Regex("^\\d{4}$")

private val LATIN_LETTER_REGEX = Regex("[a-zA-Z]")

private val VIEWS_PREFIX_REGEX = Regex("^\\D*?[\\s:\uFF1A\u200E-\u200F\u202A-\u202E]")

private const val NBSP = '\u00A0'

internal fun parseViews(text: String): String? {
    var value = text
    var prefixStripped = false

    if (!LATIN_LETTER_REGEX.containsMatchIn(value)) {
        val stripped = VIEWS_PREFIX_REGEX.replaceFirst(value, "")
        prefixStripped = stripped != value
        value = stripped
    }

    if (value.firstOrNull()?.isDigit() != true) return null
    if (!prefixStripped && value.all { it.code < 128 } && !value.contains(' ')) return null

    val head = value.substringBefore(' ').split(NBSP)
    return head.take(2).joinToString(NBSP.toString())
}

internal fun classifySongRun(run: Run): SongRunType {
    val browseId = run.navigationEndpoint?.browseEndpoint?.browseId
    if (browseId != null) {
        return if (browseId.startsWith("MPRE") || browseId.contains("release_detail")) {
            SongRunType.ALBUM
        } else {
            SongRunType.ARTIST
        }
    }

    val text = run.text
    return when {
        DURATION_REGEX.matches(text) -> SongRunType.DURATION
        YEAR_REGEX.matches(text) -> SongRunType.YEAR
        parseViews(text) != null -> SongRunType.VIEWS
        else -> SongRunType.ARTIST
    }
}

internal fun parseDurationSeconds(text: String?): Int {
    val parts = text?.trim()?.takeIf { it.isNotEmpty() }?.split(':', '.') ?: return 0
    var seconds = 0
    var multiplier = 1
    for (part in parts.asReversed()) {
        val value = part.trim().toIntOrNull() ?: return 0
        seconds += value * multiplier
        multiplier *= 60
    }
    return seconds
}

internal data class SongColumns(
    val titleIndex: Int? = null,
    val artistIndex: Int? = null,
    val albumIndex: Int? = null,
    val durationIndex: Int? = null,
)

internal fun findSongColumns(data: MusicResponsiveListItemRenderer): SongColumns {
    var titleIndex: Int? = null
    var artistIndex: Int? = null
    var albumIndex: Int? = null
    var durationIndex: Int? = null
    var unrecognizedIndex: Int? = null
    val userChannelIndexes = mutableListOf<Int>()

    for (index in data.flexColumns.indices) {
        val run =
            data.flexColumns[index]
                .musicResponsiveListItemFlexColumnRenderer
                .text
                ?.runs
                ?.firstOrNull()
                ?: continue
        val endpoint = run.navigationEndpoint

        if (endpoint == null) {
            when (classifySongRun(run)) {
                SongRunType.DURATION -> durationIndex = index
                else -> if (unrecognizedIndex == null) unrecognizedIndex = index
            }
            continue
        }

        if (endpoint.watchEndpoint != null) {
            titleIndex = index
            continue
        }

        when (
            endpoint.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType
        ) {
            MUSIC_PAGE_TYPE_ARTIST -> artistIndex = index
            MUSIC_PAGE_TYPE_ALBUM, MUSIC_PAGE_TYPE_AUDIOBOOK -> albumIndex = index
            MUSIC_PAGE_TYPE_USER_CHANNEL -> userChannelIndexes.add(index)
        }
    }

    if (artistIndex == null) artistIndex = unrecognizedIndex
    if (artistIndex == null) artistIndex = userChannelIndexes.lastOrNull()

    return SongColumns(
        titleIndex = titleIndex,
        artistIndex = artistIndex,
        albumIndex = albumIndex,
        durationIndex = durationIndex,
    )
}

internal fun parseSongAlbumAt(
    data: MusicResponsiveListItemRenderer,
    index: Int?,
): Album? {
    val run =
        data.flexColumns
            .getOrNull(index ?: return null)
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.firstOrNull() ?: return null
    val browseId = run.navigationEndpoint?.browseEndpoint?.browseId ?: return null
    return Album(id = browseId, name = run.text)
}

internal fun parseSongArtistsAt(
    data: MusicResponsiveListItemRenderer,
    index: Int?,
): List<Artist> {
    val runs =
        data.flexColumns
            .getOrNull(index ?: return emptyList())
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs ?: return emptyList()

    val artists = mutableListOf<Artist>()
    for (i in runs.indices step 2) {
        val run = runs[i]
        if (classifySongRun(run) != SongRunType.ARTIST) continue
        artists.add(
            Artist(
                id = run.navigationEndpoint?.browseEndpoint?.browseId,
                name = run.text,
            ),
        )
    }
    return artists
}
