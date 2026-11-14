package com.maxrave.domain.data.model.home

/** One of the filter chips YouTube sends above its Home feed ("Energize", "Relax", ...). */
data class HomeChip(
    val title: String,
    /** The browse params that filter FEmusic_home to this chip. */
    val params: String,
)
