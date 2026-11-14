package com.maxrave.domain.mediaservice

import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.mediaservice.handler.MAX_UNAVAILABLE_SKIPS
import com.maxrave.domain.mediaservice.handler.PlayerErrorAction
import com.maxrave.domain.mediaservice.handler.WIFI_ONLY_ERROR_CODE_NAME
import com.maxrave.domain.mediaservice.handler.playerErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlayerErrorPolicyTest {
    private fun error(
        code: Int,
        name: String = "E",
    ) = PlayerError(errorCode = code, errorCodeName = name, message = null)

    @Test
    fun anUnavailableTrackIsSkippedUntilTheCap() {
        assertEquals(PlayerErrorAction.Skip, playerErrorAction(error(2000), online = true, hasNext = true, skips = 0))
        val capped = playerErrorAction(error(2000), online = true, hasNext = true, skips = MAX_UNAVAILABLE_SKIPS)
        assertFalse(assertIs<PlayerErrorAction.Pause>(capped).network)
    }

    @Test
    fun aConnectionProblemPausesAndMayResumeByItself() {
        // Offline: the resolver fails with a plain error no code tells apart.
        assertTrue(assertIs<PlayerErrorAction.Pause>(playerErrorAction(error(2000), online = false, hasNext = true, skips = 0)).network)
        assertTrue(assertIs<PlayerErrorAction.Pause>(playerErrorAction(error(2001), online = true, hasNext = true, skips = 0)).network)
        val timeout = playerErrorAction(error(PlayerConstants.ERROR_CODE_TIMEOUT), online = true, hasNext = true, skips = 0)
        assertTrue(assertIs<PlayerErrorAction.Pause>(timeout).network)
    }

    @Test
    fun aWifiOnlyRefusalPausesWithoutWaitingForTheNetwork() {
        val refused = playerErrorAction(error(2000, WIFI_ONLY_ERROR_CODE_NAME), online = true, hasNext = true, skips = 0)
        assertFalse(assertIs<PlayerErrorAction.Pause>(refused).network)
    }
}
