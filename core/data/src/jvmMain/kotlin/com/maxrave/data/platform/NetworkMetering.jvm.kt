package com.maxrave.data.platform

import java.net.InetSocketAddress
import java.net.Socket

// A desktop is treated as unmetered: no mobile-quality or Wi-Fi-only rules.
actual fun isNetworkMetered(): Boolean = false

private const val PROBE_HOST = "music.youtube.com"
private const val PROBE_PORT = 443
private const val PROBE_TIMEOUT_MS = 1_500
private const val PROBE_CACHE_MS = 5_000L

@Volatile
private var lastProbeAtMs = 0L

@Volatile
private var lastProbeOnline = true

/**
 * Whether YouTube Music can be reached right now: one short TCP connect, remembered for a few
 * seconds.
 *
 * This used to be a constant `true`, so with the connection down every failed track looked
 * "unavailable" and the player skipped three songs before stopping, and nothing could tell when
 * the connection came back. Blocks for up to [PROBE_TIMEOUT_MS]; call it off the UI thread.
 */
actual fun hasValidatedInternet(): Boolean {
    val now = System.currentTimeMillis()
    if (now - lastProbeAtMs < PROBE_CACHE_MS) return lastProbeOnline
    val online =
        try {
            Socket().use { it.connect(InetSocketAddress(PROBE_HOST, PROBE_PORT), PROBE_TIMEOUT_MS) }
            true
        } catch (_: Exception) {
            false
        }
    lastProbeOnline = online
    lastProbeAtMs = now
    return online
}
