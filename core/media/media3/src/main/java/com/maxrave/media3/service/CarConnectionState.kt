package com.maxrave.media3.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a car head unit is currently driving this session.
 *
 * Android Auto refuses video while the vehicle is moving — the track then plays behind a black
 * "Video focus is off" panel, which is both useless and a distraction to look at. Video is
 * therefore never requested while a car is attached, whatever the in-app preference says: the
 * phone's setting is about watching a music video on a phone, and a car is not that.
 *
 * The same signal gates the Android Auto lyrics overlay
 * ([CarLyricsOverlay][com.maxrave.media3.carapp.CarLyricsOverlay]), which rewrites the session's
 * title and artist rows and so must be strictly confined to the moments a car is looking at them.
 *
 * Both a plain flag and a flow, because the two readers are shaped differently:
 * [MergingMediaSourceFactory][com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory]
 * reads it from `createMediaSource`, which is synchronous and on the player thread, while the
 * lyrics overlay wants to start and stop its work as the state changes.
 */
internal object CarConnectionState {
    /** Controller packages that mean "a car is attached", not merely another app. */
    private val CAR_PACKAGES =
        setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.autos",
            "com.google.android.embedded.projection",
        )

    /**
     * Which car packages hold a connection right now.
     *
     * A set rather than a counter or a bare boolean: Android Auto can hold more than one controller
     * at a time, and an earlier version cleared the flag the moment any one of them went away —
     * which switched video back on, and would now switch lyrics off, while the car was still very
     * much attached.
     */
    private val connected = mutableSetOf<String>()

    private val _isCarConnectedFlow = MutableStateFlow(false)

    /** Emits as cars attach and detach. */
    val isCarConnectedFlow: StateFlow<Boolean> = _isCarConnectedFlow.asStateFlow()

    @Volatile
    var isCarConnected: Boolean = false
        private set

    fun onControllerConnected(packageName: String) {
        if (packageName !in CAR_PACKAGES) return
        synchronized(connected) {
            connected += packageName
            publish()
        }
    }

    fun onControllerDisconnected(packageName: String) {
        if (packageName !in CAR_PACKAGES) return
        synchronized(connected) {
            connected -= packageName
            publish()
        }
    }

    private fun publish() {
        val attached = connected.isNotEmpty()
        isCarConnected = attached
        _isCarConnectedFlow.value = attached
    }
}
