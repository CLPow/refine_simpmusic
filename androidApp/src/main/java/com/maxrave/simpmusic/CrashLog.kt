package com.maxrave.simpmusic

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLog {
    private const val FILE_NAME = "crash-log.txt"
    private const val MAX_BYTES = 256 * 1024

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                append(appContext, "UNCAUGHT on ${thread.name}", throwable)
            } catch (_: Throwable) {
                // Never let logging stand between the crash and the platform handler.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun append(
        context: Context,
        title: String,
        throwable: Throwable?,
    ) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry =
            buildString {
                append("=== ").append(stamp).append(" · ").append(title).append('\n')
                append("app ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
                append(" · Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MODEL).append('\n')
                throwable?.let { append(it.stackTraceToString()) }
                append('\n')
            }
        val file = file(context)
        file.appendText(entry)
        if (file.length() > MAX_BYTES) {
            val text = file.readText()
            file.writeText(text.takeLast(MAX_BYTES / 2))
        }
    }
}
