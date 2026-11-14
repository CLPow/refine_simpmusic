package com.maxrave.simpmusic.desktop

object DesktopSettings {
    const val START_MINIMIZED = "desktop_start_minimized"
    const val CLOSE_TO_TRAY = "desktop_close_to_tray"
}

expect fun isStartOnBootSupported(): Boolean

expect fun isStartOnBootEnabled(): Boolean

expect fun setStartOnBoot(enabled: Boolean)
