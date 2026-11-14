package com.maxrave.kotlinytmusicscraper.models

data class AccountInfo(
    val name: String,
    val email: String,
    val pageId: String? = null,
    val thumbnails: List<Thumbnail>,
    val authUser: Int = 0,
)
