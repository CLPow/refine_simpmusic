package com.maxrave.media3.exoplayer

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.data.player.GenericTracks

@UnstableApi
fun GenericMediaItem.toMedia3MediaItem(): MediaItem {
    val builder =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.toMedia3MediaMetadata())

    uri?.let { builder.setUri(it) }
    customCacheKey?.let { builder.setCustomCacheKey(it) }

    return builder.build()
}

@UnstableApi
fun MediaItem.toGenericMediaItem(): GenericMediaItem =
    GenericMediaItem(
        mediaId = mediaId,
        uri = localConfiguration?.uri.toString(),
        metadata = mediaMetadata.toGenericMediaMetadata(),
        customCacheKey = localConfiguration?.customCacheKey,
    )

fun GenericMediaMetadata.toMedia3MediaMetadata(): MediaMetadata =
    MediaMetadata
        .Builder()
        .apply {
            title?.let { setTitle(it) }
            artist?.let { setArtist(it) }
            albumTitle?.let { setAlbumTitle(it) }
            artworkUri?.let { setArtworkUri(it.toUri()) }
            description?.let { setDescription(it) }
        }.build()

fun MediaMetadata.toGenericMediaMetadata(): GenericMediaMetadata =
    GenericMediaMetadata(
        title = title?.toString(),
        artist = artist?.toString(),
        albumTitle = albumTitle?.toString(),
        artworkUri = artworkUri.toString(),
        description = description?.toString(),
    )

fun Tracks.toGenericTracks(): GenericTracks {
    val genericGroups =
        groups.map { group ->
            GenericTracks.GenericTrackGroup(trackCount = group.length)
        }
    return GenericTracks(groups = genericGroups)
}