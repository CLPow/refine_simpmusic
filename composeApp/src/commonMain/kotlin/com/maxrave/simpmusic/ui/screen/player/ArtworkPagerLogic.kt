package com.maxrave.simpmusic.ui.screen.player

import com.maxrave.domain.data.model.browse.album.Track

internal fun deriveOrderIndex(
    queue: List<Track>,
    nowPlayingVideoId: String?,
    playerOrderIndex: Int,
): Int {
    if (queue.isEmpty()) return 0
    if (playerOrderIndex in queue.indices &&
        (nowPlayingVideoId.isNullOrEmpty() || queue[playerOrderIndex].videoId == nowPlayingVideoId)
    ) {
        return playerOrderIndex
    }
    if (nowPlayingVideoId.isNullOrEmpty()) return 0
    return queue
        .indexOfLast { it.videoId == nowPlayingVideoId }
        .coerceAtLeast(0)
}

internal sealed interface ArtworkSeekAction {
    data object Next : ArtworkSeekAction

    data object Previous : ArtworkSeekAction

    data class Skip(val index: Int) : ArtworkSeekAction

    data object NoOp : ArtworkSeekAction
}

internal fun computeSeekAction(
    newPage: Int,
    currentOrderIndex: Int,
): ArtworkSeekAction =
    when (newPage - currentOrderIndex) {
        0 -> ArtworkSeekAction.NoOp
        1 -> ArtworkSeekAction.Next
        -1 -> ArtworkSeekAction.Previous
        else -> ArtworkSeekAction.Skip(newPage)
    }
