package com.maxrave.simpmusic.expect

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.appcompat.app.AppCompatActivity
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import org.koin.mp.KoinPlatform.getKoin

actual fun hasSystemEqualizer(): Boolean = true

/**
 * The phone's own audio effects panel, as YouTube Music opens it, attached to the session that is
 * playing now (the player announces each session as it is created).
 */
actual fun openSystemEqualizer(): Boolean {
    val activity: AppCompatActivity = getKoin().get()
    val sessionId = runCatching { getKoin().get<MediaPlayerHandler>().player.audioSessionId }.getOrDefault(0)
    val intent =
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, activity.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    return runCatching { activity.startActivityForResult(intent, 0) }.isSuccess
}
