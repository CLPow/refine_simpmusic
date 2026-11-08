package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.audio.EnhanceChain
import com.maxrave.domain.audio.EnhanceSettings
import com.maxrave.domain.audio.OutputRoute
import com.maxrave.domain.audio.enhanceChain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Everything the engine reads, as one immutable value swapped atomically by the player, so a
 * buffer never sees half of a change. Compared by identity on the audio thread.
 */
data class EnhanceConfig(
    val settings: EnhanceSettings = EnhanceSettings.OFF,
    val route: OutputRoute = OutputRoute.SPEAKER,
    val volumeFraction: Float? = null,
    val compare: Boolean = false,
)

/** Shared read-out for Stats for nerds, written by whichever player is processing. */
class EnhanceMeter {
    @Volatile var active = false

    @Volatile var normalizationDb = 0f

    @Volatile var limiterReductionDb = 0f

    @Volatile var levellerDb: Float? = null

    @Volatile var lowVolumeDb = 0f

    @Volatile var loadPercent = 0f
}

/**
 * Media3 [AudioProcessor] running "Enhance audio" for one player.
 *
 * - **Off is bit-exact.** With the master switch off (and its fade finished) each buffer is copied
 *   byte for byte, so playback is identical to 2.3.8.
 * - **Two paths, two time bases.** The processed path is delayed by the limiter's lookahead. The
 *   original is either *untouched* (undelayed — what off plays) or *aligned* (through an identical
 *   limiter, so it is delayed by exactly as much). Every change — switching on or off, compare,
 *   compare then off — crossfades over [FADE_MS] between them, so the time base never jumps and
 *   nothing clicks.
 * - **Level-matched compare.** Hold-to-compare plays the aligned original: the original with the
 *   chain's whole static gain (normalization, style, EQ and car headroom) and the car leveller's
 *   current gain, through its own limiter. It differs from the processed signal only in tone.
 * - **Stages are held through a fade-out.** Switching off keeps the last active chain (crossfeed,
 *   leveller and all) running until the fade has finished, instead of dropping them mid-fade.
 * - Order: static gain → filters → crossfeed → car leveller → true-peak limiter. Only the limiter
 *   may hold peaks down.
 * - Buffers are sized in [onFlush] from the format then in force, never in [onConfigure]: Media3
 *   configures the next format while buffers of the current one are still draining.
 * - PCM float is accepted too, although Media3 1.11 converts to 16-bit before custom processors
 *   (and bypasses them entirely with float output), so no shipping path reaches it yet.
 */
@UnstableApi
class EnhanceAudioProcessor(
    private val config: () -> EnhanceConfig,
    /** YouTube's loudness for the track this player is on, or null while unknown. */
    private val loudness: () -> Float?,
    private val meter: EnhanceMeter,
) : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channels = 0
    private var isFloat = false
    private var allocatedFor: AudioProcessor.AudioFormat? = null

    private var appliedConfig: EnhanceConfig? = null
    private var appliedLoudness: Float? = null

    /** The chain the settings ask for now. */
    private var chain: EnhanceChain = EnhanceChain.BYPASS

    /** The chain the processed path runs: the last active one, held while fading out. */
    private var wetChain: EnhanceChain = EnhanceChain.BYPASS

    private var cascade: BiquadCascade? = null
    private var crossfeed: Crossfeed? = null
    private var leveller: Leveller? = null
    private var limiter: TruePeakLimiter? = null
    private var dryLimiter: TruePeakLimiter? = null

    private var frameIn = DoubleArray(0)
    private var frameWet = DoubleArray(0)
    private var frameOut = DoubleArray(0)
    private var frameDry = DoubleArray(0)
    private var frameAligned = DoubleArray(0)

    private var gain = 1.0
    private var gainTarget = 1.0
    private var gainCoef = 0.0

    /** Processed vs original: 1 = processed only. */
    private var mix = 0.0
    private var mixTarget = 0.0

    /** The original's time base: 1 = aligned (level-matched, delayed), 0 = untouched. */
    private var align = 0.0
    private var alignTarget = 0.0
    private var fadeStep = 0.0

    private var meterPeakReduction = 1.0
    private var meterFrames = 0
    private var meterNanos = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        val format = inputAudioFormat
        if (format != allocatedFor && format != AudioProcessor.AudioFormat.NOT_SET) {
            allocatedFor = format
            sampleRate = format.sampleRate
            channels = format.channelCount
            isFloat = format.encoding == C.ENCODING_PCM_FLOAT
            frameIn = DoubleArray(channels)
            frameWet = DoubleArray(channels)
            frameOut = DoubleArray(channels)
            frameDry = DoubleArray(channels)
            frameAligned = DoubleArray(channels)
            cascade = BiquadCascade(channels)
            crossfeed = if (channels == 2) Crossfeed(sampleRate) else null
            leveller = Leveller(sampleRate)
            limiter = TruePeakLimiter(channels, sampleRate, LIMITER_TARGET_DB)
            dryLimiter = TruePeakLimiter(channels, sampleRate, LIMITER_TARGET_DB)
            fadeStep = 1.0 / max(1.0, FADE_MS * sampleRate / 1000.0)
            gainCoef = 1.0 - exp(-1000.0 / (GAIN_FADE_MS * sampleRate))
            // A new format needs its own coefficients.
            appliedConfig = null
        } else {
            cascade?.reset()
            crossfeed?.reset()
            leveller?.reset()
            limiter?.reset()
            dryLimiter?.reset()
        }
        // A seek starts the new position already in the target state rather than fading into it.
        mix = mixTarget
        align = alignTarget
        gain = gainTarget
    }

    override fun onReset() {
        allocatedFor = null
        appliedConfig = null
        appliedLoudness = null
        chain = EnhanceChain.BYPASS
        wetChain = EnhanceChain.BYPASS
        cascade = null
        crossfeed = null
        leveller = null
        limiter = null
        dryLimiter = null
        mix = 0.0
        mixTarget = 0.0
        align = 0.0
        alignTarget = 0.0
        sampleRate = 0
        channels = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        if (channels == 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        sync()

        if (mix == 0.0 && mixTarget == 0.0 && align == 0.0 && alignTarget == 0.0) {
            // Off: a straight copy, so the output is the input bit for bit.
            output.put(inputBuffer)
            output.flip()
            if (meter.active) meter.active = false
            return
        }

        val started = System.nanoTime()
        inputBuffer.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = remaining / (bytesPerSample * channels)
        val limiter = limiter!!
        val dryLimiter = dryLimiter!!
        val cascade = cascade!!
        val wet = wetChain
        var reduction = 1.0

        repeat(frames) {
            for (ch in 0 until channels) {
                frameIn[ch] = if (isFloat) inputBuffer.float.toDouble() else inputBuffer.short / PCM16_SCALE
            }

            if (mix != mixTarget) mix = step(mix, mixTarget)
            if (align != alignTarget) align = step(align, alignTarget)
            if (gain != gainTarget) gain += (gainTarget - gain) * gainCoef

            // Processed path.
            for (ch in 0 until channels) frameWet[ch] = cascade.process(frameIn[ch] * gain, ch)
            if (wet.crossfeed) crossfeed?.process(frameWet)
            var levellerGain = 1.0
            if (wet.leveller) {
                levellerGain = leveller!!.next(frameWet, channels)
                for (ch in 0 until channels) frameWet[ch] *= levellerGain
            }
            limiter.process(frameWet, frameOut)
            reduction = min(reduction, limiter.gain)

            // Aligned original: same static gain and leveller gain, same limiter, same delay. Its
            // peak estimate is only computed while it can be heard; otherwise its delay line is
            // just kept current so it can take over at any moment.
            val dryHeard = align > 0.0 && mix < 1.0 || alignTarget > 0.0 && mixTarget < 1.0
            val dryGain = gain * levellerGain
            for (ch in 0 until channels) frameDry[ch] = frameIn[ch] * dryGain
            dryLimiter.process(frameDry, frameAligned, estimate = dryHeard)

            for (ch in 0 until channels) {
                val original = align * frameAligned[ch] + (1.0 - align) * frameIn[ch]
                val v = mix * frameOut[ch] + (1.0 - mix) * original
                if (isFloat) {
                    output.putFloat(v.toFloat())
                } else {
                    output.putShort(round(v * PCM16_SCALE).coerceIn(-32768.0, 32767.0).toInt().toShort())
                }
            }
        }
        // Whole frames only, but never leave bytes behind: the pipeline would re-offer them forever.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()

        meterPeakReduction = min(meterPeakReduction, reduction)
        meterNanos += System.nanoTime() - started
        meterFrames += frames
        if (meterFrames >= sampleRate) {
            meter.active = chain.active
            meter.normalizationDb = chain.normalizationDb.toFloat()
            meter.limiterReductionDb = (20.0 * log10(max(meterPeakReduction, 1e-6))).toFloat()
            meter.levellerDb = if (wet.leveller) leveller?.gainDb?.toFloat() else null
            meter.lowVolumeDb = chain.lowVolumeDb.toFloat()
            meter.loadPercent = (meterNanos / 1e9 / (meterFrames.toDouble() / sampleRate) * 100.0).toFloat()
            meterPeakReduction = 1.0
            meterFrames = 0
            meterNanos = 0L
        }
    }

    private fun step(
        value: Double,
        target: Double,
    ): Double = if (value < target) min(target, value + fadeStep) else max(target, value - fadeStep)

    /** Rebuilds the chain if — and only if — the config object or this track's loudness changed. */
    private fun sync() {
        val cfg = config()
        val trackLoudness = loudness()
        if (cfg === appliedConfig && trackLoudness == appliedLoudness) return
        appliedConfig = cfg
        appliedLoudness = trackLoudness
        val next = enhanceChain(cfg.settings, cfg.route, trackLoudness, cfg.volumeFraction)
        val startingFromOff = next.active && mix == 0.0 && align == 0.0
        chain = next
        if (next.active) {
            if (!wetChain.crossfeed && next.crossfeed) crossfeed?.reset()
            if (!wetChain.leveller && next.leveller) leveller?.reset()
            wetChain = next
            // The layout is fixed (see enhanceChain), so this only swaps coefficients and keeps history.
            cascade?.set(next.filters, sampleRate)
            gainTarget = 10.0.pow(next.gainDb / 20.0)
        }
        mixTarget = if (next.active && !cfg.compare) 1.0 else 0.0
        alignTarget = if (next.active) 1.0 else 0.0
        if (startingFromOff) {
            // From a clean state at the right gain. The untouched original carries the sound while
            // both delayed paths fill up and fade in.
            gain = gainTarget
            limiter?.reset()
            dryLimiter?.reset()
            cascade?.reset()
            leveller?.reset()
            crossfeed?.reset()
        }
    }

    private companion object {
        const val PCM16_SCALE = 32768.0
        const val FADE_MS = 25.0

        /** Time constant of the gain glide, so a late-arriving loudness value slides in, not steps. */
        const val GAIN_FADE_MS = 50.0

        /**
         * The limiter aims a little under the −1 dBTP ceiling. Its estimate is within about
         * 0.015 dB, but under heavy reduction the gain's own movement creates small new
         * between-sample peaks; 0.2 dB covers that, and the ceiling is a promise.
         */
        const val LIMITER_TARGET_DB = -1.2
    }
}
