package com.maxrave.domain.data.model.comments

enum class CommentLikeState { NONE, LIKED, DISLIKED }

data class YouTubeComment(
    val id: String,
    val author: String,
    val authorAvatar: String?,
    val authorChannelId: String?,
    val isVerified: Boolean,
    val isCreator: Boolean,
    val text: String,
    val publishedTime: String,
    val likeCount: String,
    val likeCountLiked: String,
    val likeState: CommentLikeState,
    val isHearted: Boolean,
    val isPinned: Boolean,
    val repliesLabel: String?,
    val repliesToken: String?,
    val likeAction: String?,
    val unlikeAction: String?,
    val dislikeAction: String?,
    val undislikeAction: String?,
    val replyParams: String?,
    val replyPlaceholder: String?,
)

data class YouTubeCommentsHeader(
    val countText: String?,
    val countShort: String?,
    val createParams: String?,
    val placeholder: String?,
    val userAvatar: String?,
)

data class YouTubeCommentsPage(
    val header: YouTubeCommentsHeader?,
    val comments: List<YouTubeComment>,
    val nextToken: String?,
    val nextLabel: String?,
)
