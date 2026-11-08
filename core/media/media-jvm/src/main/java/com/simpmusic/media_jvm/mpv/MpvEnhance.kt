package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.audio.EnhanceChain
import com.maxrave.domain.audio.EnhanceFilter
import java.util.Locale
import kotlin.math.pow

/**
 * The "Enhance audio" chain as one mpv `af` entry, or null when it is off.
 *
 * Built from the same [EnhanceChain] the Android processor runs, rendered with ffmpeg's own filters:
 * - `volume` for the static gain (normalization plus style headroom);
 * - `equalizer` / `lowshelf` / `highshelf` with `width_type=q`, the same Audio EQ Cookbook stages;
 * - `crossfeed` with `level_in=1`, which the Android `Crossfeed` stage matches line for line;
 * - `alimiter` last, with its auto-level off (`level=0`) so it only ever holds peaks down, run at 4×
 *   the output rate (192 kHz) so its sample-peak limit also holds between the original samples —
 *   the desktop's equivalent of Android's true-peak estimate. Without the oversampling a −1 dB
 *   sample-peak limit still let true peaks reach about +0.2 dBTP.
 *
 * The car leveller has no desktop counterpart because desktop has no car route.
 */
fun mpvEnhanceEntry(chain: EnhanceChain): String? {
    if (!chain.active) return null
    val stages = mutableListOf<String>()
    if (chain.gainDb != 0.0) stages += "volume=${num(chain.gainDb)}dB"
    chain.filters.forEach { f ->
        // The layout is fixed for Android's sake; idle 0 dB stages are simply left out here.
        if (f.gainDb == 0.0) return@forEach
        val name =
            when (f) {
                is EnhanceFilter.Peaking -> "equalizer"
                is EnhanceFilter.LowShelf -> "lowshelf"
                is EnhanceFilter.HighShelf -> "highshelf"
            }
        stages += "$name=f=${num(f.freqHz)}:width_type=q:width=${num(f.q)}:g=${num(f.gainDb)}"
    }
    if (chain.crossfeed) stages += "crossfeed=strength=0.2:range=0.5:slope=0.5:level_in=1:level_out=1"
    // A hair under the ceiling, as on Android: the 4× grid can still miss a peak by a few hundredths.
    val ceiling = 10.0.pow((chain.limiterCeilingDb - 0.1) / 20.0)
    stages += "aresample=$OVERSAMPLED_RATE"
    stages += "alimiter=limit=${num(ceiling)}:attack=1.5:release=80:level=0:latency=1"
    stages += "aresample=$OUTPUT_RATE"
    // Wrapped in [ ]: the graph contains `,` and `=`, which mpv's filter-list parser would consume.
    return "@${MpvPlayer.ENHANCE_LABEL}:lavfi=[${stages.joinToString(",")}]"
}

private fun num(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

private const val OVERSAMPLED_RATE = 192_000

/** Every YouTube stream decodes at 48 kHz (Opus) or 44.1 kHz (AAC); 48 kHz is what Windows' mixer runs at. */
private const val OUTPUT_RATE = 48_000
