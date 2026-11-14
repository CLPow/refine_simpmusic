package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicQueueRenderer(
    val content: Content?,
    /** The chips under Up next's header: Save, All, Familiar, Discover, … (radio tuning). */
    val subHeaderChipCloud: SubHeaderChipCloud? = null,
) {
    @Serializable
    data class Content(
        val playlistPanelRenderer: PlaylistPanelRenderer,
    )

    @Serializable
    data class SubHeaderChipCloud(
        val chipCloudRenderer: ChipCloud? = null,
    )

    @Serializable
    data class ChipCloud(
        val chips: List<Chip> = emptyList(),
    )

    @Serializable
    data class Chip(
        val chipCloudChipRenderer: ChipRenderer? = null,
    )

    @Serializable
    data class ChipRenderer(
        val text: Runs? = null,
        val isSelected: Boolean? = null,
        val navigationEndpoint: ChipEndpoint? = null,
    )

    /**
     * A radio chip re-tunes the queue through `queueUpdateCommand.fetchContentsCommand.watchEndpoint`
     * (measured on the web client, 2026-11); the plain endpoints are kept as fallbacks. The Save
     * chip carries none of them, which is how it is told apart: its text is localised.
     */
    @Serializable
    data class ChipEndpoint(
        val queueUpdateCommand: QueueUpdateCommand? = null,
        val watchEndpoint: WatchEndpoint? = null,
        val watchPlaylistEndpoint: WatchEndpoint? = null,
    ) {
        val radioEndpoint: WatchEndpoint?
            get() = queueUpdateCommand?.fetchContentsCommand?.watchEndpoint ?: watchEndpoint ?: watchPlaylistEndpoint
    }

    @Serializable
    data class QueueUpdateCommand(
        val fetchContentsCommand: FetchContentsCommand? = null,
    )

    @Serializable
    data class FetchContentsCommand(
        val watchEndpoint: WatchEndpoint? = null,
    )
}
