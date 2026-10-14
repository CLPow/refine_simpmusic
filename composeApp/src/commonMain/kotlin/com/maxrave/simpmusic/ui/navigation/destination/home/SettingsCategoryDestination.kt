package com.maxrave.simpmusic.ui.navigation.destination.home

import kotlinx.serialization.Serializable

/** One page of Settings; [category] is a [com.maxrave.simpmusic.ui.screen.home.SettingsCategory] name. */
@Serializable
data class SettingsCategoryDestination(
    val category: String,
)
