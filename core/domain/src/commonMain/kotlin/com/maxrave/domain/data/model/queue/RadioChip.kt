package com.maxrave.domain.data.model.queue

/**
 * One of the chips YouTube Music shows over Up next for a radio ("Familiar", "Discover", …).
 * [playlistId] and [params] are the re-tuned radio; both are null for the "Save" chip.
 */
data class RadioChip(
    val title: String,
    val playlistId: String?,
    val params: String?,
    val selected: Boolean,
) {
    val isSave: Boolean get() = playlistId == null
}
