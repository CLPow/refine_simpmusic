package com.maxrave.simpmusic.expect

import android.content.Context
import org.koin.mp.KoinPlatform.getKoin
import java.io.File

actual fun readCrashLog(): String? =
    runCatching {
        val context: Context = getKoin().get()
        File(context.filesDir, "crash-log.txt").takeIf { it.exists() }?.readText()
    }.getOrNull()
