package com.maxrave.kotlinytmusicscraper.models

import com.maxrave.domain.utils.MusicVideoType
import kotlinx.serialization.Serializable

@Serializable
data class PlaylistPanelRenderer(
    val title: String?,
    val titleText: Runs?,
    val shortBylineText: Runs?,
    val contents: List<Content>,
    val currentIndex: Int?,
    val isInfinite: Boolean? = null,
    val numItemsToShow: Int?,
    val playlistId: String? = null,
    val continuations: List<Continuation>?,
) {
    /**
     * Maps YouTube's [currentIndex] (a position in [contents]) to the position the same row has once
     * rows without a song rendition are dropped. Null when the current row itself was dropped.
     */
    fun songIndexOf(index: Int?): Int? {
        if (index == null || contents.getOrNull(index)?.songTrack == null) return null
        return contents.take(index).count { it.songTrack != null }
    }

    @Serializable
    data class Content(
        val playlistPanelVideoWrapperRenderer: PlaylistPanelWrapperVideoRenderer?,
        val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer?,
        val automixPreviewVideoRenderer: AutomixPreviewVideoRenderer?,
    ) {
        /**
         * The track this row stands for, whether YouTube sent it bare or wrapped.
         *
         * Callers used to read [playlistPanelVideoRenderer] alone, so every wrapped row — exactly
         * the tracks that exist as both a song and a video — resolved to null and was dropped from
         * the queue by the `mapNotNull` that built it.
         */
        val track: PlaylistPanelVideoRenderer?
            get() = playlistPanelVideoRenderer
                ?: playlistPanelVideoWrapperRenderer?.primaryRenderer?.playlistPanelVideoRenderer

        /** The alternate rendition of [track], when YouTube shipped one alongside it. */
        val counterpartTrack: PlaylistPanelVideoRenderer?
            get() = playlistPanelVideoWrapperRenderer
                ?.counterpart
                ?.firstNotNullOfOrNull { it.counterpartRenderer?.playlistPanelVideoRenderer }

        /**
         * The rendition Refine plays: the audio one, whichever side of the wrapper it is on.
         * Null when the row has no song rendition at all (a fan video, a live stream, a podcast
         * episode), which drops it from the queue.
         */
        val songTrack: PlaylistPanelVideoRenderer?
            get() = MusicVideoType.preferSong(track, counterpartTrack) { it.musicVideoType }

        /**
         * A queue row that exists in more than one rendition.
         *
         * YouTube wraps a track like this when the same recording is available both as the audio
         * version and as a music video — it is what powers the Song/Video switch in the official
         * client. [primaryRenderer] is the one it chose to serve; [counterpart] holds the other,
         * already complete, in the same response and at no extra request.
         */
        @Serializable
        data class PlaylistPanelWrapperVideoRenderer(
            val primaryRenderer: PrimaryRenderer,
            val counterpart: List<Counterpart>? = null,
        ) {
            @Serializable
            data class PrimaryRenderer(
                val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer?,
            )

            @Serializable
            data class Counterpart(
                val counterpartRenderer: CounterpartRenderer?,
            ) {
                @Serializable
                data class CounterpartRenderer(
                    val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer?,
                )
            }
        }
    }
}