package com.maxrave.domain.lyrics

import kotlin.math.abs

/**
 * Choosing which search result is actually the track that is playing.
 *
 * Lyrics providers are searched by text, and a text search for a well-known song returns the studio
 * recording, the live recording, three covers, a karaoke backing track and a sped-up edit - all
 * with the right title and the right artist. Taking the first result, or the first within a loose
 * duration window, is how a song ends up displaying somebody else's words.
 *
 * The rules encode what makes two recordings the *same* one rather than merely similarly named:
 *
 * - A candidate may not carry a version marker the wanted track lacks. "Song (Live)" is not "Song",
 *   and its lyrics carry crowd noise, ad-libs and timing from a different performance. This is the
 *   single rule that fixes most wrong matches.
 * - The reverse is allowed but penalised: when the wanted track *is* the live version and only the
 *   studio words exist, they are usually close enough to be worth showing.
 * - Titles must match once the decorations are stripped, and the artists must overlap -
 *   collaborations are credited inconsistently across services, so one artist in common is enough.
 * - Duration decides between what is left, and disagreeing by more than a few seconds disqualifies:
 *   two recordings of the same song that differ by more than that are different recordings.
 *
 * Pure and free of any provider's types so it can be exercised directly, which matters because the
 * failure it prevents is silent - wrong lyrics look exactly like right lyrics until you read them.
 */
object TrackMatch {
    /** How far a candidate's duration may sit from the track's before it is a different recording. */
    private const val MAX_DURATION_DELTA_SECONDS = 5

    /**
     * Words that mark a recording as a particular version rather than as the song itself.
     *
     * Matched as whole words against the normalised title, so "livestream" and "alive" do not count
     * as "live". "Remastered" is deliberately absent: it is the same performance, cleaned up.
     */
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

    /**
     * Phrases that say how a recording was uploaded, not what it is. Removed before comparing.
     *
     * Whole phrases, plus the single word "official" which is never part of a title. Bare "video",
     * "audio" and "lyrics" are deliberately absent: they are ordinary words, and stripping them
     * would turn "Video Games" into "Games" and let it match a different song.
     */
    private val NOISE_PHRASES =
        listOf(
            "official music video",
            "official lyric video",
            "official video",
            "official audio",
            "lyric video",
            "official",
        )

    /**
     * One search result, reduced to what the decision needs.
     *
     * @param value carried through untouched and handed back when this candidate wins.
     */
    data class Candidate<T>(
        val title: String?,
        val artist: String?,
        val durationSeconds: Int?,
        val value: T,
        /** Providers that offer both timed and plain text; timed wins ties. */
        val isSynced: Boolean = true,
        /** Backing tracks match everything on title and duration and contain no words. */
        val isInstrumental: Boolean = false,
    )

    /**
     * The candidate that is the same recording as the wanted track, or null when none is.
     *
     * Returning null is a real answer, and a better one than a confident wrong match: the caller
     * moves on to the next source, and a track with no lyrics anywhere shows its own name.
     */
    fun <T> best(
        title: String,
        artist: String,
        durationSeconds: Int?,
        candidates: List<Candidate<T>>,
    ): T? {
        val wantedTitle = normalise(title)
        val wantedMarkers = markersIn(wantedTitle)
        val wantedArtistTokens = artistTokens(artist)

        return candidates
            .mapNotNull { candidate ->
                score(candidate, wantedTitle, wantedMarkers, wantedArtistTokens, durationSeconds)
                    ?.let { it to candidate.value }
            }.minByOrNull { it.first }
            ?.second
    }

    /** Lower is better; null means this candidate is not the same recording. */
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
        // The rule that matters: a version nobody asked for is the wrong recording.
        if ((candidateMarkers - wantedMarkers).isNotEmpty()) return null

        if (!titlesMatch(wantedTitle, candidateTitle, wantedMarkers, candidateMarkers)) return null

        // An empty artist on either side is missing information rather than a contradiction; the
        // title and duration still have to agree, which is enough to keep it honest.
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
            // Unknown on one side: usable, but any candidate whose duration was checked is better.
            score += MAX_DURATION_DELTA_SECONDS + 1
        }

        // Asked for a version nobody has words for; the song's own words are the next best thing.
        score += (wantedMarkers - candidateMarkers).size * 20

        if (!candidate.isSynced) score += 100

        return score
    }

    /**
     * Whether two normalised titles name the same song.
     *
     * Containment as well as equality, because services disagree about what belongs in a title: one
     * writes "Song feat. Guest", another just "Song". Both sides have their version markers removed
     * first so "song live" and "song" compare as the same words - whether that difference is
     * permitted at all was already decided above.
     */
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

        // One title may carry words the other leaves out, but not most of them: plain substring
        // containment would call "Someone" and "Someone Like You" the same song, and with the same
        // artist and a similar length nothing else would catch it.
        val (shorter, longer) = if (a.size <= b.size) a to b else b to a
        // A one-word title has to match exactly. "Games" is not "Video Games" and "Someone" is not
        // "Someone Like You", and with the same artist and a similar length nothing else would
        // catch either. Beyond that, the shorter title must still be most of the longer one.
        if (shorter.size < 2 || shorter.size * 2 < longer.size) return false
        return longer.windowed(shorter.size).any { it == shorter }
    }

    /**
     * Lowercased words only: punctuation and upload noise removed, whitespace collapsed.
     *
     * Featured-artist credits go too. They are written a dozen ways, appear on one service and not
     * the next, and never distinguish two recordings of the same song.
     */
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

    /**
     * The artist credit as a set of words.
     *
     * Word-level rather than whole-string because collaborations are credited inconsistently -
     * "A & B", "A, B", "A and B", or only "A" - and one shared name is enough to tell that two
     * results are the same act. Words of one or two letters are too weak to carry that alone.
     */
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
