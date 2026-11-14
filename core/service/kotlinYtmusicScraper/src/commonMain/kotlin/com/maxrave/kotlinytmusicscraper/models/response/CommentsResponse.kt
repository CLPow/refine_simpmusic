package com.maxrave.kotlinytmusicscraper.models.response

import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CommentsResponse(
    val onResponseReceivedEndpoints: List<ResponseEndpoint>? = null,
    val frameworkUpdates: FrameworkUpdates? = null,
    val actions: List<JsonElement>? = null,
) {
    @Serializable
    data class ResponseEndpoint(
        val reloadContinuationItemsCommand: ContinuationItems? = null,
        val appendContinuationItemsAction: ContinuationItems? = null,
    )

    @Serializable
    data class ContinuationItems(
        val continuationItems: List<Item>? = null,
    )

    @Serializable
    data class Item(
        val commentsHeaderRenderer: HeaderRenderer? = null,
        val commentThreadRenderer: ThreadRenderer? = null,
        val commentViewModel: CommentViewModel? = null,
        val continuationItemRenderer: ContinuationItemRenderer? = null,
    )

    @Serializable
    data class HeaderRenderer(
        val countText: Runs? = null,
        val commentsCount: Runs? = null,
        val createRenderer: CreateRenderer? = null,
    )

    @Serializable
    data class CreateRenderer(
        val commentSimpleboxRenderer: SimpleboxRenderer? = null,
    )

    @Serializable
    data class SimpleboxRenderer(
        val submitButton: JsonElement? = null,
        val placeholderText: Runs? = null,
        val authorThumbnail: Thumbnails? = null,
        val prepareAccountEndpoint: JsonElement? = null,
    )

    @Serializable
    data class ThreadRenderer(
        val commentViewModel: CommentViewModelHolder? = null,
        val replies: Replies? = null,
    )

    @Serializable
    data class CommentViewModelHolder(
        val commentViewModel: CommentViewModel? = null,
    )

    @Serializable
    data class CommentViewModel(
        val commentId: String? = null,
        val commentKey: String? = null,
        val toolbarStateKey: String? = null,
        val toolbarSurfaceKey: String? = null,
        val pinnedText: String? = null,
    )

    @Serializable
    data class Replies(
        val commentRepliesRenderer: RepliesRenderer? = null,
    )

    @Serializable
    data class RepliesRenderer(
        val viewReplies: JsonElement? = null,
        val subThreads: List<Item>? = null,
        val contents: List<Item>? = null,
    )

    @Serializable
    data class ContinuationItemRenderer(
        val continuationEndpoint: JsonElement? = null,
        val button: JsonElement? = null,
    )

    @Serializable
    data class FrameworkUpdates(
        val entityBatchUpdate: EntityBatchUpdate? = null,
    )

    @Serializable
    data class EntityBatchUpdate(
        val mutations: List<Mutation>? = null,
    )

    @Serializable
    data class Mutation(
        val entityKey: String? = null,
        val payload: Payload? = null,
    )

    @Serializable
    data class Payload(
        val commentEntityPayload: CommentEntity? = null,
        val engagementToolbarStateEntityPayload: ToolbarState? = null,
        val engagementToolbarSurfaceEntityPayload: JsonElement? = null,
    )

    @Serializable
    data class CommentEntity(
        val properties: Properties? = null,
        val author: Author? = null,
        val toolbar: Toolbar? = null,
    ) {
        @Serializable
        data class Properties(
            val commentId: String? = null,
            val content: Content? = null,
            val publishedTime: String? = null,
        )

        @Serializable
        data class Content(
            val content: String? = null,
        )

        @Serializable
        data class Author(
            val channelId: String? = null,
            val displayName: String? = null,
            val avatarThumbnailUrl: String? = null,
            val isVerified: Boolean? = null,
            val isCreator: Boolean? = null,
        )

        @Serializable
        data class Toolbar(
            val likeCountNotliked: String? = null,
            val likeCountLiked: String? = null,
        )
    }

    @Serializable
    data class ToolbarState(
        val likeState: String? = null,
        val heartState: String? = null,
    )
}
