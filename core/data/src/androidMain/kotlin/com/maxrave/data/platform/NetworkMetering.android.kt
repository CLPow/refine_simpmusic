package com.maxrave.data.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.koin.mp.KoinPlatform.getKoin

actual fun isNetworkMetered(): Boolean =
    runCatching {
        val context = getKoin().get<Context>()
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(false)

// Unknown counts as online: the caller pauses when offline, and a failed check must not turn
// every error into a pause.
actual fun hasValidatedInternet(): Boolean =
    runCatching {
        val manager = getKoin().get<Context>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)
