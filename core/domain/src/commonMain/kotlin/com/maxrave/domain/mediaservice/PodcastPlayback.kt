package com.maxrave.domain.mediaservice

object PodcastPlayback {
    const val SPEED_KEY = "podcast_speed"
    const val EPISODE_VIDEO_TYPE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private const val PLAYED_FRACTION = 0.95
    private const val MIN_RESUME_MS = 5_000L

    fun speedOf(stored: String?): Float = stored?.toFloatOrNull()?.takeIf { it in SPEEDS } ?: 1f

    fun isPlayed(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = durationMs > 0 && positionMs >= durationMs * PLAYED_FRACTION

    fun shouldResume(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = positionMs >= MIN_RESUME_MS && !isPlayed(positionMs, durationMs)
}
