package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError

sealed interface PlayerErrorAction {
    data object Skip : PlayerErrorAction

    data class Pause(
        val toast: ToastType,
        val network: Boolean = false,
    ) : PlayerErrorAction
}

const val MAX_UNAVAILABLE_SKIPS = 3

const val WIFI_ONLY_ERROR_CODE_NAME = "WIFI_ONLY"

private val NETWORK_ERROR_CODES = setOf(2001, 2002)

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
            !online -> PlayerErrorAction.Pause(ToastType.PlayerError(error.errorCodeName), network = true)
        hasNext && skips < MAX_UNAVAILABLE_SKIPS -> PlayerErrorAction.Skip
        else -> PlayerErrorAction.Pause(ToastType.PlayerError(error.errorCodeName))
    }
