package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class CreateCommentBody(
    val context: Context,
    val createCommentParams: String,
    val commentText: String,
)

@Serializable
data class CreateCommentReplyBody(
    val context: Context,
    val createReplyParams: String,
    val commentText: String,
)

@Serializable
data class PerformCommentActionBody(
    val context: Context,
    val actions: List<String>,
)
