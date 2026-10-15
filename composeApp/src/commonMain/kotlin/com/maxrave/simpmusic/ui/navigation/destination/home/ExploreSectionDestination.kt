package com.maxrave.simpmusic.ui.navigation.destination.home

import kotlinx.serialization.Serializable

/** A page behind one of Explore's buttons; [section] is one of the constants below. */
@Serializable
data class ExploreSectionDestination(
    val section: String,
) {
    companion object {
        const val NEW_RELEASES = "new_releases"
        const val CHARTS = "charts"
        const val MOODS = "moods"
    }
}
