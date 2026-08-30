package com.maxrave.media3.service

/**
 * Whether a car head unit is currently driving this session.
 *
 * Android Auto refuses video while the vehicle is moving — the track then plays behind a black
 * "Video focus is off" panel, which is both useless and a distraction to look at. Video is
 * therefore never requested while a car is attached, whatever the in-app preference says: the
 * phone's setting is about watching a music video on a phone, and a car is not that.
 *
 * A plain flag rather than a flow: [MergingMediaSourceFactory][com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory]
 * reads it from `createMediaSource`, which is synchronous and on the player thread, and the value
 * is written from the session callback on connect and disconnect. `@Volatile` is enough for a
 * single boolean crossing those two threads.
 */
internal object CarConnectionState {
    /** Controller packages that mean "a car is attached", not merely another app. */
    private val CAR_PACKAGES =
        setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.autos",
            "com.google.android.embedded.projection",
        )

    @Volatile
    var isCarConnected: Boolean = false
        private set

    fun onControllerConnected(packageName: String) {
        if (packageName in CAR_PACKAGES) {
            isCarConnected = true
        }
    }

    fun onControllerDisconnected(packageName: String) {
        if (packageName in CAR_PACKAGES) {
            isCarConnected = false
        }
    }
}
