package com.maxrave.domain.lyrics

import kotlin.math.abs

object TrackMatch {
    private const val MAX_DURATION_DELTA_SECONDS = 5

    // Matched as whole words against the normalised title, so "livestream" and "alive" do not count as "live".
    private val VERSION_MARKERS =
        listOf(
            "live",
            "acoustic",
            "unplugged",
            "cover",
            "karaoke",
            "instrumental",
            "remix",
            "reprise",
            "demo",
            "nightcore",
            "slowed",
            "reverb",
            "session",
            "sessions",
            "orchestral",
            "piano",
        )

    // Whole phrases, plus the single word "official" which is never part of a title.
    private val NOISE_PHRASES =
        listOf(
            "official music video",
            "official lyric video",
            "official video",
            "official audio",
            "lyric video",
            "official",
        )

    data class Candidate<T>(
        val title: String?,
        val artist: String?,
        val durationSeconds: Int?,
        val value: T,
        val isSynced: Boolean = true,
        val isInstrumental: Boolean = false,
    )

    fun <T> best(
        title: String,
        artist: String,
        durationSeconds: Int?,
        candidates: List<Candidate<T>>,
    ): T? {
        val wantedTitle = normalise(title)
        val wantedMarkers = markersIn(wantedTitle)
        val wantedArtistTokens = artistTokens(artist)
        val wantedVersion = VersionMarkers.lyricsVersion(title)

        return candidates
            .filter { candidate -> VersionMarkers.lyricsVersion(candidate.title.orEmpty()) == wantedVersion }
            .mapNotNull { candidate ->
                score(candidate, wantedTitle, wantedMarkers, wantedArtistTokens, durationSeconds)
                    ?.let { it to candidate.value }
            }.minByOrNull { it.first }
            ?.second
    }

    private fun <T> score(
        candidate: Candidate<T>,
        wantedTitle: String,
        wantedMarkers: Set<String>,
        wantedArtistTokens: Set<String>,
        wantedDuration: Int?,
    ): Int? {
        if (candidate.isInstrumental && "instrumental" !in wantedMarkers) return null

        val candidateTitle = normalise(candidate.title.orEmpty())
        if (candidateTitle.isBlank()) return null

        val candidateMarkers = markersIn(candidateTitle)
        if ((candidateMarkers - wantedMarkers).isNotEmpty()) return null

        if (!titlesMatch(wantedTitle, candidateTitle, wantedMarkers, candidateMarkers)) return null

        val candidateArtistTokens = artistTokens(candidate.artist.orEmpty())
        if (wantedArtistTokens.isNotEmpty() &&
            candidateArtistTokens.isNotEmpty() &&
            wantedArtistTokens.intersect(candidateArtistTokens).isEmpty()
        ) {
            return null
        }

        var score = 0

        if (wantedDuration != null && wantedDuration > 0 && candidate.durationSeconds != null) {
            val delta = abs(candidate.durationSeconds - wantedDuration)
            if (delta > MAX_DURATION_DELTA_SECONDS) return null
            score += delta
        } else {
            score += MAX_DURATION_DELTA_SECONDS + 1
        }

        score += (wantedMarkers - candidateMarkers).size * 20

        if (!candidate.isSynced) score += 100

        return score
    }

    private fun titlesMatch(
        wanted: String,
        candidate: String,
        wantedMarkers: Set<String>,
        candidateMarkers: Set<String>,
    ): Boolean {
        val a = stripWords(wanted, wantedMarkers).split(" ").filter { it.isNotBlank() }
        val b = stripWords(candidate, candidateMarkers).split(" ").filter { it.isNotBlank() }
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true

        val (shorter, longer) = if (a.size <= b.size) a to b else b to a
        if (shorter.size < 2 || shorter.size * 2 < longer.size) return false
        return longer.windowed(shorter.size).any { it == shorter }
    }

    private fun normalise(raw: String): String {
        var text = raw.lowercase()
        text = FEATURE_REGEX.replace(text, " ")
        text = NON_WORD_REGEX.replace(text, " ")
        text = WHITESPACE_REGEX.replace(text, " ").trim()
        for (phrase in NOISE_PHRASES) {
            text = text.replace(Regex("\\b" + Regex.escape(phrase) + "\\b"), " ")
        }
        return WHITESPACE_REGEX.replace(text, " ").trim()
    }

    private fun markersIn(normalisedTitle: String): Set<String> {
        val words = normalisedTitle.split(" ").toSet()
        return VERSION_MARKERS.filter { it in words }.toSet()
    }

    private fun stripWords(
        normalisedTitle: String,
        words: Set<String>,
    ): String =
        normalisedTitle
            .split(" ")
            .filter { it.isNotBlank() && it !in words && it !in DECORATION_WORDS }
            .joinToString(" ")

    private fun artistTokens(raw: String): Set<String> =
        normalise(raw)
            .split(" ")
            .filter { it.length > 2 }
            .toSet()

    private val DECORATION_WORDS = setOf("version", "remaster", "remastered", "edit", "mix")
    private val FEATURE_REGEX = Regex("\\b(feat|ft|featuring|prod)\\b\\.?.*\$")
    private val NON_WORD_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE_REGEX = Regex("\\s+")
}
