package com.maxrave.simpmusic.viewModel

import android.content.Context
import org.koin.mp.KoinPlatform.getKoin

actual fun getPackageName(): String {
    val application: Context = getKoin().get()
    return application.packageName
}

actual fun getFileDir(): String {
    val application: Context = getKoin().get()
    return application.filesDir.absolutePath
}
