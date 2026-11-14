package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicQueueRenderer(
    val content: Content?,
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
