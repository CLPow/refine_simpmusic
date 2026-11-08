package com.maxrave.domain.audio

import kotlin.math.abs

/**
 * Reads an AutoEq "FixedBandEQ" profile (the `… FixedBandEQ.txt` file AutoEq publishes for every
 * headphone) into gains on [ENHANCE_EQ_BANDS_HZ].
 *
 * ```
 * Preamp: -6.4 dB
 * Filter 1: ON PK Fc 31 Hz Gain 5.8 dB Q 1.41
 * ```
 *
 * The preamp line is ignored: the engine applies AutoEq's own rule (minus the largest boost).
 *
 * Returns null — changing nothing — unless *every* enabled filter is a peaking filter on one of the
 * ten centres with Q ≈ 1.41. A ParametricEQ profile (shelves, arbitrary frequencies and Qs) would
 * otherwise be silently squeezed onto the wrong bands and sound nothing like what it promises.
 */
fun parseAutoEqFixedBand(text: String): List<Float>? {
    val gains = MutableList(ENHANCE_EQ_BANDS_HZ.size) { 0f }
    var matched = 0
    for (line in ANY_FILTER.findAll(text)) {
        val m = FILTER_LINE.matchEntire(line.value.trim()) ?: return null
        val fc = m.groupValues[1].toDoubleOrNull() ?: return null
        val gain = m.groupValues[2].toFloatOrNull() ?: return null
        val q = m.groupValues[3].toDoubleOrNull() ?: return null
        val index = ENHANCE_EQ_BANDS_HZ.indices.minBy { abs(ENHANCE_EQ_BANDS_HZ[it] - fc) }
        if (abs(ENHANCE_EQ_BANDS_HZ[index] - fc) > ENHANCE_EQ_BANDS_HZ[index] * 0.05) return null
        if (abs(q - ENHANCE_EQ_Q) > 0.05) return null
        gains[index] = gain.coerceIn(-ENHANCE_EQ_MAX_DB, ENHANCE_EQ_MAX_DB)
        matched++
    }
    return if (matched == 0) null else gains
}

/** Every enabled filter line, whatever its type. */
private val ANY_FILTER = Regex("""(?im)^\s*Filter\s+\d+\s*:\s*ON\b.*$""")

private val FILTER_LINE =
    Regex(
        """Filter\s+\d+\s*:\s*ON\s+PK\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
        RegexOption.IGNORE_CASE,
    )
