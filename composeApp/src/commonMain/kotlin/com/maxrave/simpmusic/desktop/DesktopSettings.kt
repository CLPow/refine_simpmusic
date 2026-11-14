package com.maxrave.simpmusic.desktop

/** Desktop-only options from ytmdesktop, stored as plain DataStore strings ("TRUE"/"FALSE"). */
object DesktopSettings {
    const val START_MINIMIZED = "desktop_start_minimized"
    const val CLOSE_TO_TRAY = "desktop_close_to_tray"
}

/** Whether "Start on boot" can be offered (Windows, running from the installed app). */
expect fun isStartOnBootSupported(): Boolean

expect fun isStartOnBootEnabled(): Boolean

expect fun setStartOnBoot(enabled: Boolean)
