package com.maxrave.simpmusic.expect

/** Whether this platform has a system equalizer to open (YouTube Music's Settings › Playback › Equalizer). */
expect fun hasSystemEqualizer(): Boolean

/** Opens the system equalizer. Returns false when none is installed. */
expect fun openSystemEqualizer(): Boolean
