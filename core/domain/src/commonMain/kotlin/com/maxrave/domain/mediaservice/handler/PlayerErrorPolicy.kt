package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError

/** What the player does about an error: move on to the next track, or stop and say why. */
sealed interface PlayerErrorAction {
    data object Skip : PlayerErrorAction

    data class Pause(
        val toast: ToastType,
    ) : PlayerErrorAction
}

/**
 * Consecutive tracks skipped as unavailable before the player stops: YouTube Music moves past a
 * track that will not play, and the cap keeps a deeper fault from skipping through the queue.
 */
const val MAX_UNAVAILABLE_SKIPS = 3

/** `errorCodeName` of a stream refused under "Stream via Wi-Fi only" (set by the player adapter). */
const val WIFI_ONLY_ERROR_CODE_NAME = "WIFI_ONLY"

// ExoPlayer's IO_NETWORK_CONNECTION_FAILED and IO_NETWORK_CONNECTION_TIMEOUT.
private val NETWORK_ERROR_CODES = setOf(2001, 2002)

/**
 * The one decision both players (Android and desktop) make on an error, so they cannot drift.
 *
 * A refused stream and anything that looks like the connection rather than the track pauses:
 * skipping there would only burn through the queue with the same failure. The resolver also fails
 * with a plain IOException when offline, which no error code tells apart, so [online] (a validated
 * internet connection) decides; it errs towards pausing. Only then is a track called unavailable
 * and skipped, while there is a next one and the cap is not reached.
 */
fun playerErrorAction(
    error: PlayerError,
    online: Boolean,
    hasNext: Boolean,
    skips: Int,
): PlayerErrorAction =
    when {
        error.errorCodeName == WIFI_ONLY_ERROR_CODE_NAME -> PlayerErrorAction.Pause(ToastType.WifiOnly)
        error.errorCode == PlayerConstants.ERROR_CODE_TIMEOUT ||
            error.errorCode in NETWORK_ERROR_CODES ||
            !online -> PlayerErrorAction.Pause(ToastType.PlayerError(error.errorCodeName))
        hasNext && skips < MAX_UNAVAILABLE_SKIPS -> PlayerErrorAction.Skip
        else -> PlayerErrorAction.Pause(ToastType.PlayerError(error.errorCodeName))
    }
