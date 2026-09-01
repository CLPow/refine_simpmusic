package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] carrying the two things a crossfade does to one track: its **gain**, and
 * optionally a low-pass or high-pass sweep for the DJ-style blend.
 *
 * Key design:
 * - [enabled], [cutoffFrequencyHz], [filterType] and [targetGain] can all be changed at runtime.
 * - Coefficient recalculation is lazy: only when cutoff or filter type changes.
 * - Supports PCM 16-bit audio (mono and stereo).
 *
 * The gain lives here rather than on the player because `ExoPlayer.volume` is a coarse control: the
 * crossfade coroutine can only rewrite it a handful of times a second, from the main thread, and
 * each write lands on a whole buffer at once. Ramping a fade that way is audible as stepping. Here
 * the coroutine only publishes where the gain should be heading, and [queueInput] slides towards it
 * across the samples of each buffer on the audio thread, so main-thread scheduling cannot roughen
 * the fade.
 */
@UnstableApi
class CrossfadeFilterAudioProcessor : BaseAudioProcessor() {
    /**
     * Whether the filter is active. When false, audio passes through unfiltered — though
     * [targetGain] still applies, since a crossfade fades whether or not the DJ sweep is on.
     */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    filter.reset()
                }
            }
        }

    /**
     * Current cutoff frequency in Hz. Updated during crossfade animation.
     */
    @Volatile
    var cutoffFrequencyHz: Float = 20000f
        set(value) {
            if (field != value) {
                field = value
                coefficientsDirty = true
            }
        }

    /**
     * Filter type: LOW_PASS for outgoing track, HIGH_PASS for incoming track.
     */
    @Volatile
    var filterType: BiquadFilter.FilterType = BiquadFilter.FilterType.LOW_PASS
        set(value) {
            if (field != value) {
                field = value
                coefficientsDirty = true
            }
        }

    /**
     * Where the gain should be heading, 0f..1f. Published by the crossfade ramp.
     *
     * Never applied abruptly: [queueInput] interpolates from wherever the gain actually is to this
     * value across the buffer it is handed, so a jump between published values becomes a slide
     * rather than a click.
     */
    @Volatile
    var targetGain: Float = UNITY_GAIN

    /**
     * Snaps the gain, skipping the slide.
     *
     * For the start and end of a fade, where the value is already correct and interpolating from a
     * stale one would audibly ramp from the wrong place.
     */
    fun resetGain(value: Float) {
        targetGain = value
        appliedGain = value
    }

    /** Audio-thread only: where the gain actually is, between buffers. */
    private var appliedGain: Float = UNITY_GAIN

    private val filter = BiquadFilter()

    @Volatile
    private var coefficientsDirty = true

    private var sampleRate = 0
    private var channelCount = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        coefficientsDirty = true
        return inputAudioFormat
    }

    /**
     * Always in the chain.
     *
     * Returning false when idle would let ExoPlayer drop the processor, and it would then have to
     * be reinstated mid-transition — a reconfiguration during the one moment that has to sound
     * seamless. The idle path in [queueInput] is a straight buffer copy, so the cost of staying is
     * a memcpy.
     */
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val target = targetGain
        val filtering = enabled && sampleRate != 0
        val silentOnGain = appliedGain == UNITY_GAIN && target == UNITY_GAIN

        if (!filtering && silentOnGain) {
            val output = replaceOutputBuffer(remaining)
            copyBuffer(inputBuffer, output, remaining)
            output.flip()
            return
        }

        if (filtering && coefficientsDirty) {
            filter.updateCoefficients(
                cutoffHz = cutoffFrequencyHz,
                sampleRate = sampleRate,
                type = filterType,
            )
            coefficientsDirty = false
        }

        val output = replaceOutputBuffer(remaining)
        inputBuffer.order(ByteOrder.nativeOrder())

        val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
        val frames = if (bytesPerFrame > 0) remaining / bytesPerFrame else 0
        // Spread the whole move across this buffer, so consecutive buffers join without a step even
        // when the published target jumps.
        val gainStep = if (frames > 0) (target - appliedGain) / frames else 0f

        when (channelCount) {
            1 -> processMono(inputBuffer, output, gainStep, filtering)
            2 -> processStereo(inputBuffer, output, gainStep, filtering)
            else -> copyBuffer(inputBuffer, output, remaining)
        }

        // Land exactly on the target rather than on an accumulation of floating-point steps.
        appliedGain = target
        output.flip()
    }

    /**
     * Copy bytes from src to dst. Use this instead of dst.put(src) when they may be the same
     * buffer: `ByteBuffer.put(ByteBuffer)` throws if source and destination are identical.
     */
    private fun copyBuffer(
        src: ByteBuffer,
        dst: ByteBuffer,
        size: Int,
    ) {
        if (src === dst) {
            dst.position(0)
            dst.limit(size)
            return
        }
        dst.put(src)
    }

    private fun processMono(
        input: ByteBuffer,
        output: ByteBuffer,
        gainStep: Float,
        filtering: Boolean,
    ) {
        var gain = appliedGain
        while (input.remaining() >= BYTES_PER_SAMPLE) {
            val sample = input.short.toDouble() / Short.MAX_VALUE
            val shaped = if (filtering) filter.processSampleMono(sample) else sample
            output.putShort(toPcm(shaped * gain))
            gain += gainStep
        }
    }

    private fun processStereo(
        input: ByteBuffer,
        output: ByteBuffer,
        gainStep: Float,
        filtering: Boolean,
    ) {
        var gain = appliedGain
        while (input.remaining() >= BYTES_PER_SAMPLE * 2) {
            val left = input.short.toDouble() / Short.MAX_VALUE
            val right = input.short.toDouble() / Short.MAX_VALUE
            val shapedL: Double
            val shapedR: Double
            if (filtering) {
                val (l, r) = filter.processStereo(left, right)
                shapedL = l
                shapedR = r
            } else {
                shapedL = left
                shapedR = right
            }
            output.putShort(toPcm(shapedL * gain))
            output.putShort(toPcm(shapedR * gain))
            gain += gainStep
        }
    }

    private fun toPcm(value: Double): Short = (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    override fun onFlush() {
        super.onFlush()
        filter.reset()
    }

    override fun onReset() {
        super.onReset()
        enabled = false
        cutoffFrequencyHz = 20000f
        filterType = BiquadFilter.FilterType.LOW_PASS
        resetGain(UNITY_GAIN)
        filter.reset()
    }

    private companion object {
        const val UNITY_GAIN = 1f
        const val BYTES_PER_SAMPLE = 2
    }
}
