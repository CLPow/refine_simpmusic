package com.maxrave.media3.service.mediasourcefactory

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.mediaservice.RenditionController

@UnstableApi
internal class MergingMediaSourceFactory(
    private val defaultMediaSourceFactory: DefaultMediaSourceFactory,
    private val videoMediaSourceFactory: DefaultMediaSourceFactory,
    private val renditions: RenditionController,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        videoMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        videoMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = defaultMediaSourceFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val mediaId = mediaItem.mediaId
        val rendition = renditions.renditionFor(mediaId)
        renditions.markBuilt(mediaId, rendition)
        val audioItem =
            if (rendition.audioId == (mediaItem.localConfiguration?.customCacheKey ?: mediaId)) {
                mediaItem
            } else {
                mediaItem.buildUpon().setCustomCacheKey(rendition.audioId).build()
            }
        val audio = defaultMediaSourceFactory.createMediaSource(audioItem)
        val videoId = rendition.videoId ?: return audio
        val videoItem =
            mediaItem
                .buildUpon()
                .setMediaId("${MERGING_DATA_TYPE.VIDEO}$videoId")
                .setCustomCacheKey("${MERGING_DATA_TYPE.VIDEO}$videoId")
                .build()
        return MergingMediaSource(videoMediaSourceFactory.createMediaSource(videoItem), audio)
    }
}
