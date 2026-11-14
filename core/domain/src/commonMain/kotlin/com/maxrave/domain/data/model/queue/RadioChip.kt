package com.maxrave.domain.data.model.queue

data class RadioChip(
    val title: String,
    val playlistId: String?,
    val params: String?,
    val selected: Boolean,
) {
    val isSave: Boolean get() = playlistId == null
}
