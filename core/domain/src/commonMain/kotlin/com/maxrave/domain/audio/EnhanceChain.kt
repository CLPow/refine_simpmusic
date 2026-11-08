package com.maxrave.domain.audio

import kotlin.math.max
import kotlin.math.min

/**
 * One biquad stage, by its musical meaning rather than its coefficients.
 *
 * Android turns these into Audio EQ Cookbook coefficients itself; desktop hands the same numbers to
 * ffmpeg's `equalizer`, `lowshelf` and `highshelf` filters, which implement the same cookbook with
 * `width_type=q`. One table, two renderers: that is what keeps the two platforms sounding alike.
 */
sealed interface EnhanceFilter {
    val freqHz: Double
    val q: Double
    val gainDb: Double

    data class Peaking(override val freqHz: Double, override val q: Double, override val gainDb: Double) : EnhanceFilter

    data class LowShelf(override val freqHz: Double, override val q: Double, override val gainDb: Double) : EnhanceFilter

    data class HighShelf(override val freqHz: Double, override val q: Double, override val gainDb: Double) : EnhanceFilter
}

/**
 * The complete processing for one track on one output, worked out from the settings.
 *
 * Order, on both platforms: [gainDb] → [filters] → crossfeed → car leveller → true-peak limiter.
 * The limiter is last and is the only stage allowed to hold peaks down, so no stage before it may
 * clip and nothing after it may add gain.
 */
data class EnhanceChain(
    val active: Boolean,
    /** Static gain ahead of everything: loudness normalization plus the style's headroom. */
    val gainDb: Double = 0.0,
    val filters: List<EnhanceFilter> = emptyList(),
    val crossfeed: Boolean = false,
    val leveller: Boolean = false,
    /** Ceiling of the final limiter, in dB true peak. */
    val limiterCeilingDb: Double = LIMITER_CEILING_DB,
    /** The normalization part of [gainDb] alone, for Stats for nerds. */
    val normalizationDb: Double = 0.0,
    /** Bass lift from low-volume compensation right now, for Stats for nerds. */
    val lowVolumeDb: Double = 0.0,
) {
    companion object {
        val BYPASS = EnhanceChain(active = false)
    }
}

const val LIMITER_CEILING_DB: Double = -1.0

/** Butterworth Q, the shelf slope that neither overshoots nor sags at the corner. */
private const val SHELF_Q = 0.7071

/** YouTube's `loudnessDb` beyond this is treated as missing rather than trusted. */
private const val LOUDNESS_SANE_RANGE_DB = 20f

/**
 * Number of biquad stages in every active chain. The layout is fixed — style (2), car (2), the
 * Advanced equalizer (10), low-volume compensation (2) — with unused stages at 0 dB, so moving a
 * slider through 0, crossing a volume step or changing route only swaps coefficients and never
 * resets the filters' history (which would click).
 */
const val ENHANCE_STAGE_COUNT: Int = 16

/**
 * Per-style curves (one shelf slot, one peaking slot) and the static gain that keeps each style
 * about as loud as Natural, so a style is never "better" merely by being louder. Checked by the
 * evaluation gate: each style within 0.5 LU of Natural on the held-out tracks.
 */
private fun styleStages(style: EnhanceStyle): Triple<EnhanceFilter, EnhanceFilter, Double> =
    when (style) {
        EnhanceStyle.NATURAL -> Triple(EnhanceFilter.LowShelf(105.0, SHELF_Q, 0.0), EnhanceFilter.Peaking(2_500.0, 0.9, 0.0), 0.0)
        EnhanceStyle.BASS -> Triple(EnhanceFilter.LowShelf(105.0, SHELF_Q, 4.5), EnhanceFilter.Peaking(2_500.0, 0.9, 0.0), -1.6)
        EnhanceStyle.VOCAL -> Triple(EnhanceFilter.LowShelf(150.0, SHELF_Q, -1.5), EnhanceFilter.Peaking(2_500.0, 0.9, 3.0), 0.0)
    }

private fun styleBassDb(style: EnhanceStyle): Double = if (style == EnhanceStyle.BASS) 4.5 else 0.0

/** Road noise masks the lows and the top end first; a gentle tilt puts them back. */
private const val CAR_BASS_DB = 3.0
private const val CAR_TREBLE_DB = 2.0
private const val CAR_HEADROOM_DB = -1.0

/** Low-volume compensation reaches full strength at or below this media volume … */
private const val LOW_VOLUME_FULL = 0.15

/** … and fades to nothing at this one, following the flattening equal-loudness contours. */
private const val LOW_VOLUME_NONE = 0.65

/** All bass lifts together (style, car, the equalizer's low bands, compensation) never exceed this. */
private const val MAX_TOTAL_BASS_DB = 8.0

/** The gain YouTube's loudness asks for, turn-down only: −max(0, loudnessDb). */
fun normalizationGainDb(loudnessDb: Float?): Double {
    if (loudnessDb == null || loudnessDb.isNaN() || loudnessDb !in -LOUDNESS_SANE_RANGE_DB..LOUDNESS_SANE_RANGE_DB) return 0.0
    // Not -max(0, x): that yields -0.0 for quiet tracks, which compares unequal to 0.0.
    return if (loudnessDb > 0f) -loudnessDb.toDouble() else 0.0
}

/** How much low-volume compensation applies at [volumeFraction] (0 = none, 1 = full). */
fun lowVolumeAmount(volumeFraction: Float): Double =
    ((LOW_VOLUME_NONE - volumeFraction) / (LOW_VOLUME_NONE - LOW_VOLUME_FULL)).coerceIn(0.0, 1.0)

/**
 * Builds the chain for [settings] on [route].
 *
 * @param loudnessDb YouTube's per-track loudness relative to its reference, or null while unknown.
 * @param volumeFraction the device's media volume (0..1) for low-volume compensation, or null where
 *   the platform has no such reading.
 */
fun enhanceChain(
    settings: EnhanceSettings,
    route: OutputRoute,
    loudnessDb: Float?,
    volumeFraction: Float? = null,
): EnhanceChain {
    if (!settings.enabled) return EnhanceChain.BYPASS

    val normalization = if (settings.normalizeVolume) normalizationGainDb(loudnessDb) else 0.0
    var headroom = 0.0

    val (styleShelf, stylePeak, styleHeadroom) = styleStages(settings.style)
    headroom += styleHeadroom

    val car = route == OutputRoute.CAR && settings.carSound
    if (car) headroom += CAR_HEADROOM_DB

    // The listener's own curve. Its preamp is AutoEq's rule — minus the largest (clamped) boost —
    // so a curve imported from AutoEq plays at the level it was designed for.
    val bands = List(ENHANCE_EQ_BANDS_HZ.size) { settings.eqBandsDb.getOrElse(it) { 0f }.coerceIn(-ENHANCE_EQ_MAX_DB, ENHANCE_EQ_MAX_DB).toDouble() }
    headroom -= max(0.0, bands.maxOrNull() ?: 0.0)

    // Low-volume compensation: not in the car, where the phone's volume says little about what the
    // listener hears. Its own lift is budgeted as headroom so it does not just drive the limiter.
    var lowBass = 0.0
    var lowTreble = 0.0
    if (settings.lowVolumeCompensation && volumeFraction != null && route != OutputRoute.CAR) {
        val amount = lowVolumeAmount(volumeFraction)
        val bassSoFar = styleBassDb(settings.style) + (if (car) CAR_BASS_DB else 0.0) + max(0.0, bands.take(3).maxOrNull() ?: 0.0)
        lowBass = min(6.0 * amount, max(0.0, MAX_TOTAL_BASS_DB - bassSoFar))
        lowTreble = 2.0 * amount
        headroom -= 2.0 * amount
    }

    val filters =
        buildList {
            add(styleShelf)
            add(stylePeak)
            add(EnhanceFilter.LowShelf(90.0, SHELF_Q, if (car) CAR_BASS_DB else 0.0))
            add(EnhanceFilter.HighShelf(6_000.0, SHELF_Q, if (car) CAR_TREBLE_DB else 0.0))
            bands.forEachIndexed { i, g -> add(EnhanceFilter.Peaking(ENHANCE_EQ_BANDS_HZ[i].toDouble(), ENHANCE_EQ_Q, g)) }
            add(EnhanceFilter.LowShelf(100.0, SHELF_Q, lowBass))
            add(EnhanceFilter.HighShelf(10_000.0, SHELF_Q, lowTreble))
        }

    return EnhanceChain(
        active = true,
        gainDb = normalization + headroom,
        filters = filters,
        crossfeed = settings.crossfeed && route == OutputRoute.HEADPHONES,
        leveller = car,
        normalizationDb = normalization,
        lowVolumeDb = lowBass,
    )
}
