package com.maxrave.simpmusic.desktop

actual fun isStartOnBootSupported(): Boolean = WindowsIntegration.isStartOnBootSupported()

actual fun isStartOnBootEnabled(): Boolean = WindowsIntegration.isStartOnBootEnabled()

actual fun setStartOnBoot(enabled: Boolean) = WindowsIntegration.setStartOnBoot(enabled)
