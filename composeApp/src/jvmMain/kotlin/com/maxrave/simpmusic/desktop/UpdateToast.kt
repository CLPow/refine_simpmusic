package com.maxrave.simpmusic.desktop

import com.maxrave.logger.Logger
import kotlinx.coroutines.delay
import simpmusic.composeapp.generated.resources.Res
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

object UpdateToast {
    suspend fun show(
        title: String,
        body: String,
        onClick: () -> Unit,
    ) {
        if (!SystemTray.isSupported()) return
        val icon =
            try {
                val image = ImageIO.read(ByteArrayInputStream(Res.readBytes("drawable/circle_app_icon.png")))
                TrayIcon(image, title).apply {
                    isImageAutoSize = true
                    addActionListener { onClick() }
                }
            } catch (e: Exception) {
                Logger.w("UpdateToast", "No tray icon for the update notice: ${e.message}")
                return
            }
        val tray = SystemTray.getSystemTray()
        try {
            tray.add(icon)
            icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
            delay(15_000)
        } catch (e: Exception) {
            Logger.w("UpdateToast", "Update notice not shown: ${e.message}")
        } finally {
            runCatching { tray.remove(icon) }
        }
    }
}
