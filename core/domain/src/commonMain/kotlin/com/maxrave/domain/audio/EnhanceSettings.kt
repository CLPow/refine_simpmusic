package com.maxrave.domain.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The listener's "Enhance audio" settings, stored as one value so a change is never read half-applied.
 *
 * The master switch defaults to off: an install that has never opened the Sound page, and every
 * upgrade from 2.3.8, plays exactly what it played before. That is the engine's first rule — with
 * [enabled] off the audio is passed through bit for bit, on Android and on desktop alike. The
 * fields below only take effect once the listener turns it on.
 */
@Serializable
data class EnhanceSettings(
    /** The master switch. Off means a bit-exact bypass, whatever the fields below say. */
    val enabled: Boolean = false,
    val style: EnhanceStyle = EnhanceStyle.NATURAL,
    /**
     * Turn loud tracks down to YouTube's reference loudness, using the `loudnessDb` YouTube sends
     * with each stream. Turn-down only: a quiet master is left alone rather than boosted into the
     * limiter. On by default inside Enhance (the master switch stays off): with it on, the limiter
     * is idle on loud masters instead of working full time.
     */
    val normalizeVolume: Boolean = true,
    /** Advanced: the listener's own ten-band curve on [ENHANCE_EQ_BANDS_HZ], in dB. Empty = flat. */
    val eqBandsDb: List<Float> = emptyList(),
    /** Advanced: crossfeed on headphones and Bluetooth audio, for less "inside the head" hard-panned mixes. */
    val crossfeed: Boolean = false,
    /** Advanced (Android): keeps bass and treble audible at low volume, where the ear loses them first. */
    val lowVolumeCompensation: Boolean = false,
    /** Use the car tuning (road-noise tilt and a slow volume leveller) while Android Auto is connected. */
    val carSound: Boolean = false,
    /**
     * Android: the listener has opened the phone's own equalizer from the Sound page, so Refine
     * announces its audio sessions to it from then on. Until then nothing is announced, so a
     * phone equalizer app cannot attach and change the sound behind the listener's back.
     */
    val phoneEqualizerLinked: Boolean = false,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val OFF = EnhanceSettings()

        private val json = Json { ignoreUnknownKeys = true }

        /** A stored value, or [OFF] for blank or unreadable text, so a bad write can never turn processing on. */
        fun decode(stored: String?): EnhanceSettings =
            if (stored.isNullOrBlank()) OFF else runCatching { json.decodeFromString(serializer(), stored) }.getOrDefault(OFF)
    }
}

/** The tonal styles. Each is a fixed, measurable curve: nothing here listens to the music and changes itself. */
@Serializable
enum class EnhanceStyle {
    /** No tonal change: only the volume steps that are switched on, and the peak limiter. */
    NATURAL,

    /** A broad low-shelf lift for small speakers and earbuds. */
    BASS,

    /** Presence lift around 2.5 kHz with a little less low-mid, so voices sit forward. */
    VOCAL,
}

/** Where the audio is going, which decides the per-output parts of the chain. */
enum class OutputRoute {
    SPEAKER,
    HEADPHONES,
    CAR,
}

/** ISO octave centres of the Advanced equalizer, the spacing AutoEq's fixed-band profiles use. */
val ENHANCE_EQ_BANDS_HZ: IntArray = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

/** Q of every Advanced equalizer band: AutoEq's value for ten octave bands, and ffmpeg's `width=1.41`. */
const val ENHANCE_EQ_Q: Double = 1.41

/** Range of an Advanced equalizer band, in dB. */
const val ENHANCE_EQ_MAX_DB: Float = 12f

/**
 * What the engine is doing right now, for the Sound page's "Stats for nerds".
 *
 * The source line and the processing line are kept apart on purpose: what YouTube sent is never
 * presented as something the engine made better.
 */
data class EnhanceStatus(
    val sourceCodec: String? = null,
    /** YouTube's format id (e.g. 251 = Opus ~160 kbps, 250 = Opus ~70 kbps), when known. */
    val sourceItag: Int? = null,
    val sourceBitrateKbps: Int? = null,
    val sourceSampleRateHz: Int? = null,
    val route: OutputRoute = OutputRoute.SPEAKER,
    val active: Boolean = false,
    val normalizationDb: Float = 0f,
    /** Deepest limiter gain reduction over the last second, in dB (0 when the limiter is idle). */
    val limiterReductionDb: Float = 0f,
    /** Car leveller gain, in dB, when the car tuning is running. */
    val levellerDb: Float? = null,
    /** Processing time as a share of real time, in percent. */
    val loadPercent: Float = 0f,
    /** Bass lift from low-volume compensation right now, in dB. */
    val lowVolumeDb: Float = 0f,
    /** The platform could not install the chain (e.g. a desktop build without the filters). */
    val unavailable: Boolean = false,
)
