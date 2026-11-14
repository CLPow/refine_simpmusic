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
        val track: PlaylistPanelVideoRenderer?
            get() = playlistPanelVideoRenderer
                ?: playlistPanelVideoWrapperRenderer?.primaryRenderer?.playlistPanelVideoRenderer

        val counterpartTrack: PlaylistPanelVideoRenderer?
            get() = playlistPanelVideoWrapperRenderer
                ?.counterpart
                ?.firstNotNullOfOrNull { it.counterpartRenderer?.playlistPanelVideoRenderer }

        val songTrack: PlaylistPanelVideoRenderer?
            get() = MusicVideoType.preferSong(track, counterpartTrack) { it.musicVideoType }

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