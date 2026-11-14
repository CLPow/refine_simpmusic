package com.maxrave.simpmusic.expect

import com.maxrave.logger.Logger
import multiplatform.network.cmptoast.showToast
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.net.URI

private const val TAG = "OpenUrl"

actual fun openUrl(url: String) {
    if (openWithDesktopApi(url)) return
    if (openWithSystemLauncher(url)) return

    Logger.e(TAG, "Could not open $url by any means")
    showToast("Could not open the link")
}

private fun openWithDesktopApi(url: String): Boolean =
    runCatching {
        if (!Desktop.isDesktopSupported()) return@runCatching false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching false
        desktop.browse(URI(url))
        true
    }.getOrElse { error ->
        Logger.w(TAG, "Desktop.browse failed for $url: ${error.message}")
        false
    }

private fun openWithSystemLauncher(url: String): Boolean {
    val os = System.getProperty("os.name", "").lowercase()
    val candidates =
        when {
            os.contains("mac") -> listOf(listOf("open", url))
            os.contains("windows") -> listOf(listOf("rundll32", "url.dll,FileProtocolHandler", url))
            else ->
                listOfNotNull(
                    listOf("xdg-open", url),
                    listOf("gio", "open", url),
                    System.getenv("BROWSER")?.takeIf { it.isNotBlank() }?.let { listOf(it, url) },
                )
        }

    return candidates.any { command ->
        runCatching { ProcessBuilder(command).start() }
            .onFailure { error -> Logger.w(TAG, "${command.first()} failed for $url: ${error.message}") }
            .isSuccess
    }
}

actual fun shareUrl(
    title: String,
    url: String,
) {
    val stringSelection = StringSelection(url)
    val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(stringSelection, null)
    showToast("Copied to clipboard")
}
