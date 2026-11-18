package com.maxrave.simpmusic.ui.navigation.destination.list

import kotlinx.serialization.Serializable

@Serializable
data class PodcastDestination(
    val podcastId: String,
)

@Serializable
data class EpisodeDestination(
    val videoId: String,
    val podcastId: String? = null,
)

@Serializable
data object EpisodesForLaterDestination
