package com.maxrave.media3.audio

import com.maxrave.domain.audio.EnhanceFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The DSP stages of "Enhance audio", as plain Kotlin with no Android or Media3 types, so the JVM
 * tests and the offline render harness run exactly the code the phone runs.
 *
 * Every stage works on doubles in full-scale units (±1.0) and allocates only when it is built.
 */

/**
 * Normalised biquad coefficients (b0, b1, b2, a1, a2 with a0 = 1) for one [EnhanceFilter], from the
 * Audio EQ Cookbook with the Q form of alpha — the same formulas ffmpeg's `equalizer`, `lowshelf`
 * and `highshelf` use with `width_type=q`, which is what desktop renders the same table with.
 */
fun biquadCoefficients(
    filter: EnhanceFilter,
    sampleRate: Int,
): DoubleArray {
    // A stage at or above Nyquist has no meaning at this rate; the identity keeps the layout fixed.
    if (filter.freqHz >= sampleRate / 2.0 || filter.gainDb == 0.0) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
    val a = 10.0.pow(filter.gainDb / 40.0)
    val w0 = 2.0 * PI * filter.freqHz / sampleRate
    val cosW = cos(w0)
    val alpha = sin(w0) / (2.0 * filter.q)
    val b0: Double
    val b1: Double
    val b2: Double
    val a0: Double
    val a1: Double
    val a2: Double
    when (filter) {
        is EnhanceFilter.Peaking -> {
            b0 = 1.0 + alpha * a
            b1 = -2.0 * cosW
            b2 = 1.0 - alpha * a
            a0 = 1.0 + alpha / a
            a1 = -2.0 * cosW
            a2 = 1.0 - alpha / a
        }
        is EnhanceFilter.LowShelf -> {
            val s = 2.0 * sqrt(a) * alpha
            b0 = a * ((a + 1) - (a - 1) * cosW + s)
            b1 = 2.0 * a * ((a - 1) - (a + 1) * cosW)
            b2 = a * ((a + 1) - (a - 1) * cosW - s)
            a0 = (a + 1) + (a - 1) * cosW + s
            a1 = -2.0 * ((a - 1) + (a + 1) * cosW)
            a2 = (a + 1) + (a - 1) * cosW - s
        }
        is EnhanceFilter.HighShelf -> {
            val s = 2.0 * sqrt(a) * alpha
            b0 = a * ((a + 1) + (a - 1) * cosW + s)
            b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW)
            b2 = a * ((a + 1) + (a - 1) * cosW - s)
            a0 = (a + 1) - (a - 1) * cosW + s
            a1 = 2.0 * ((a - 1) - (a + 1) * cosW)
            a2 = (a + 1) - (a - 1) * cosW - s
        }
    }
    return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

/**
 * A cascade of biquads, one state set per channel, transposed direct form II.
 *
 * Stages at 0 dB are skipped (the layout is fixed at [com.maxrave.domain.audio.ENHANCE_STAGE_COUNT],
 * most of it usually idle); a stage's history is cleared when it goes idle so it starts clean if
 * it comes back.
 */
class BiquadCascade(
    private val channels: Int,
) {
    private var coeffs = DoubleArray(0)
    private var state = DoubleArray(0)
    private var live = BooleanArray(0)
    private var stages = 0

    /**
     * Installs new stages. With the same stage count the history is kept, so a change of gain
     * mid-song does not click.
     */
    fun set(
        filters: List<EnhanceFilter>,
        sampleRate: Int,
    ) {
        val next = DoubleArray(filters.size * 5)
        val nextLive = BooleanArray(filters.size)
        filters.forEachIndexed { i, f ->
            val c = biquadCoefficients(f, sampleRate)
            c.copyInto(next, i * 5)
            nextLive[i] = !(c[0] == 1.0 && c[1] == 0.0 && c[2] == 0.0 && c[3] == 0.0 && c[4] == 0.0)
        }
        if (filters.size != stages) {
            state = DoubleArray(filters.size * 2 * channels)
        } else {
            for (i in 0 until stages) {
                if (live[i] && !nextLive[i]) {
                    for (ch in 0 until channels) {
                        val s = (ch * stages + i) * 2
                        state[s] = 0.0
                        state[s + 1] = 0.0
                    }
                }
            }
        }
        coeffs = next
        live = nextLive
        stages = filters.size
    }

    fun reset() = state.fill(0.0)

    fun process(
        x: Double,
        channel: Int,
    ): Double {
        var y = x
        var s = channel * stages * 2
        var c = 0
        for (i in 0 until stages) {
            if (live[i]) {
                val out = coeffs[c] * y + state[s]
                state[s] = coeffs[c + 1] * y - coeffs[c + 3] * out + state[s + 1]
                state[s + 1] = coeffs[c + 2] * y - coeffs[c + 4] * out
                y = out
            }
            c += 5
            s += 2
        }
        return y
    }
}

/**
 * Headphone crossfeed: a low shelf on the side signal, so hard-panned bass and lower mids reach
 * both ears as they would from speakers.
 *
 * A line-for-line match of ffmpeg's `crossfeed` filter (strength, range, slope; level_in = 1), so
 * the desktop's `crossfeed=level_in=1` sounds the same.
 */
class Crossfeed(
    sampleRate: Int,
    strength: Double = 0.2,
    range: Double = 0.5,
    slope: Double = 0.5,
) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var w1 = 0.0
    private var w2 = 0.0

    init {
        val a = 10.0.pow(strength * -30.0 / 40.0)
        val w0 = 2.0 * PI * (1.0 - range) * 2100.0 / sampleRate
        val alpha = sin(w0) / 2.0 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val cosW = cos(w0)
        val a0 = (a + 1) + (a - 1) * cosW + 2 * sqrt(a) * alpha
        a1 = 2 * ((a - 1) + (a + 1) * cosW) / a0
        a2 = -((a + 1) + (a - 1) * cosW - 2 * sqrt(a) * alpha) / a0
        b0 = a * ((a + 1) - (a - 1) * cosW + 2 * sqrt(a) * alpha) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * cosW) / a0
        b2 = a * ((a + 1) - (a - 1) * cosW - 2 * sqrt(a) * alpha) / a0
    }

    fun reset() {
        w1 = 0.0
        w2 = 0.0
    }

    /** Processes one stereo frame in place in [frame] (index 0 = left, 1 = right). */
    fun process(frame: DoubleArray) {
        val mid = (frame[0] + frame[1]) * 0.5
        val side = (frame[0] - frame[1]) * 0.5
        val oside = side * b0 + w1
        w1 = b1 * side + w2 + a1 * oside
        w2 = b2 * side + a2 * oside
        frame[0] = mid + oside
        frame[1] = mid - oside
    }
}

/**
 * The car tuning's slow leveller: lifts a song's quiet passages towards its own average, the way
 * road noise demands, without the pumping of a fast compressor.
 *
 * - The level is K-weighted (the ITU-R BS.1770 loudness filter), so a bass-heavy passage is not
 *   mistaken for a loud one.
 * - It compares the last three seconds with the last thirty and closes the gap, so it evens out
 *   passages *within* a song but never changes how loud the song is overall — that is
 *   normalization's job.
 * - Both averages start from the first 300 ms of sound (not from one sample), and the gain stays at
 *   0 dB for the first 10 s after a start or seek, until the long average means something.
 * - It moves at most [SLEW_DB_PER_S], lifts by at most +4 dB, trims by at most −1.5 dB, and holds
 *   still in near-silence so a fade-out or a gap is not dragged up into the noise floor.
 */
class Leveller(
    private val sampleRate: Int,
) {
    private val shortCoef = exp(-1.0 / (3.0 * sampleRate))
    private val longCoef = exp(-1.0 / (30.0 * sampleRate))
    private val maxStep = SLEW_DB_PER_S / sampleRate
    private val seedFrames = (SEED_S * sampleRate).toInt()
    private val warmupFrames = (WARMUP_S * sampleRate).toLong()

    // K-weighting: a +4 dB high shelf at 1.68 kHz, then a 38 Hz high-pass (mono, on the frame mean).
    private val shelf = biquadCoefficients(EnhanceFilter.HighShelf(1_681.97, 0.7071, 4.0), sampleRate)
    private val highPass = highPassCoefficients(38.13, 0.5003, sampleRate)
    private val kState = DoubleArray(4)

    private var seedSum = 0.0
    private var seedCount = 0
    private var shortMs = -1.0
    private var longMs = -1.0
    private var measured = 0L
    var gainDb = 0.0
        private set

    fun reset() {
        kState.fill(0.0)
        seedSum = 0.0
        seedCount = 0
        shortMs = -1.0
        longMs = -1.0
        measured = 0L
        gainDb = 0.0
    }

    /** Feeds one frame and returns the linear gain for it. */
    fun next(
        frame: DoubleArray,
        channels: Int,
    ): Double {
        var mono = 0.0
        for (ch in 0 until channels) mono += frame[ch]
        mono /= channels
        // Transposed direct form II, two stages.
        val a = shelf[0] * mono + kState[0]
        kState[0] = shelf[1] * mono - shelf[3] * a + kState[1]
        kState[1] = shelf[2] * mono - shelf[4] * a
        val k = highPass[0] * a + kState[2]
        kState[2] = highPass[1] * a - highPass[3] * k + kState[3]
        kState[3] = highPass[2] * a - highPass[4] * k
        val ms = k * k

        if (shortMs < 0) {
            if (ms > SILENCE_MS || seedCount > 0) {
                seedSum += ms
                seedCount++
            }
            if (seedCount < seedFrames) return 10.0.pow(gainDb / 20.0)
            shortMs = seedSum / seedCount
            longMs = shortMs
        }
        shortMs = shortCoef * shortMs + (1 - shortCoef) * ms
        val shortDb = 10.0 * log10(shortMs + 1e-12)
        if (shortDb > SILENCE_DB) {
            longMs = longCoef * longMs + (1 - longCoef) * ms
            if (++measured > warmupFrames) {
                val wanted = (10.0 * log10(longMs + 1e-12) - shortDb).coerceIn(MIN_DB, MAX_DB)
                gainDb += (wanted - gainDb).coerceIn(-maxStep, maxStep)
            }
        }
        return 10.0.pow(gainDb / 20.0)
    }

    private companion object {
        const val MIN_DB = -1.5
        const val MAX_DB = 4.0
        const val SILENCE_DB = -50.0
        val SILENCE_MS = 10.0.pow(SILENCE_DB / 10.0)
        const val SLEW_DB_PER_S = 2.0
        const val SEED_S = 0.3
        const val WARMUP_S = 10.0
    }
}

/** Audio EQ Cookbook high-pass, normalised (b0, b1, b2, a1, a2). */
internal fun highPassCoefficients(
    freqHz: Double,
    q: Double,
    sampleRate: Int,
): DoubleArray {
    val w0 = 2.0 * PI * freqHz / sampleRate
    val cosW = cos(w0)
    val alpha = sin(w0) / (2.0 * q)
    val a0 = 1.0 + alpha
    return doubleArrayOf((1 + cosW) / 2 / a0, -(1 + cosW) / a0, (1 + cosW) / 2 / a0, -2 * cosW / a0, (1 - alpha) / a0)
}

/**
 * Lookahead true-peak limiter: the single stage allowed to hold peaks down.
 *
 * - Peaks are estimated between the samples as well, by 8× windowed-sinc interpolation, because a
 *   lossy decode of a loud master overshoots full scale between samples and the DAC reproduces
 *   that overshoot as clipping.
 * - The gain needed for each sample is held for the lookahead window and then averaged over the
 *   same window, which ramps the gain down smoothly *before* the peak and guarantees the peak
 *   itself meets [ceiling]. Release is exponential.
 * - Below the ceiling it is a pure delay: the gain stays exactly 1.
 */
class TruePeakLimiter(
    private val channels: Int,
    sampleRate: Int,
    ceilingDb: Double,
    lookaheadMs: Double = 1.5,
    releaseMs: Double = 80.0,
) {
    private val ceiling = 10.0.pow(ceilingDb / 20.0)
    private val window = max(1, (lookaheadMs * sampleRate / 1000.0).roundToInt())
    private val releaseCoef = exp(-1000.0 / (releaseMs * sampleRate))

    /** Interpolator taps per phase; the estimate refers to the sample [HALF] frames back. */
    private val history = DoubleArray(channels * TAPS)
    private var historyPos = 0

    /** Total delay in frames from input to output. */
    val latencyFrames = window - 1 + HALF

    private val delay = DoubleArray(channels * (latencyFrames + 1))
    private var delayPos = 0

    // Sliding minimum over the window: a monotonic deque of ticks (ring of window + 1, so a push
    // after the expiry pass can never overwrite the head) into a ring of required gains.
    private val required = DoubleArray(window)
    private val dequeCap = window + 1
    private val dequeTick = LongArray(dequeCap)
    private var dequeHead = 0
    private var dequeSize = 0
    private var tick = 0L

    // Running mean of the held gain over the window.
    private val held = DoubleArray(window)
    private var heldPos = 0
    private var heldSum = window.toDouble()
    private var release = 1.0
    private var sinceResum = 0
    private var previousNeed = 1.0

    /** Gain applied to the frame just output, for metering. */
    var gain = 1.0
        private set

    init {
        held.fill(1.0)
        required.fill(1.0)
    }

    fun reset() {
        history.fill(0.0)
        delay.fill(0.0)
        held.fill(1.0)
        required.fill(1.0)
        heldSum = window.toDouble()
        release = 1.0
        previousNeed = 1.0
        dequeHead = 0
        dequeSize = 0
        tick = 0L
        gain = 1.0
    }

    /**
     * Pushes one frame from [input] and writes the frame [latencyFrames] earlier, limited, to [output].
     *
     * With [estimate] false the (costly) between-sample estimate is skipped and the frame counts as
     * needing no reduction: used to keep an idle instance's delay line current, so it can take over
     * at any moment without replaying stale audio.
     */
    fun process(
        input: DoubleArray,
        output: DoubleArray,
        estimate: Boolean = true,
    ) {
        // 1. True-peak estimate around the sample HALF frames back.
        var peak = 0.0
        for (ch in 0 until channels) {
            val base = ch * TAPS
            history[base + historyPos] = input[ch]
            if (!estimate) continue
            for (phase in 0 until PHASES) {
                var acc = 0.0
                val k = PHASE_KERNELS[phase]
                for (j in 0 until TAPS) acc += history[base + (historyPos + 1 + j) % TAPS] * k[j]
                peak = max(peak, abs(acc))
            }
        }
        historyPos = (historyPos + 1) % TAPS
        // The estimate covers the stretch from one sample to the next; the peak in there is shaped
        // by the gain at both ends, so it binds both samples, not only the first.
        val needHere = if (peak > ceiling) ceiling / peak else 1.0
        val need = min(needHere, previousNeed)
        previousNeed = needHere

        // 2. Hold the smallest required gain across the window. Expire first, then push.
        while (dequeSize > 0 && dequeTick[dequeHead] <= tick - window) {
            dequeHead = (dequeHead + 1) % dequeCap
            dequeSize--
        }
        required[(tick % window).toInt()] = need
        while (dequeSize > 0 && required[(dequeTick[(dequeHead + dequeSize - 1) % dequeCap] % window).toInt()] >= need) dequeSize--
        dequeTick[(dequeHead + dequeSize) % dequeCap] = tick
        dequeSize++
        val windowMin = required[(dequeTick[dequeHead] % window).toInt()]
        tick++

        // 3. Release towards the window minimum; attack is instant here and smoothed by step 4.
        release = if (windowMin < release) windowMin else windowMin + (release - windowMin) * releaseCoef
        // 4. Average the held gain over the window: a ramp that reaches the needed gain at the peak.
        heldSum += release - held[heldPos]
        held[heldPos] = release
        heldPos = (heldPos + 1) % window
        if (++sinceResum >= RESUM_INTERVAL) {
            heldSum = held.sum()
            sinceResum = 0
        }
        gain = min(1.0, heldSum / window)

        // 5. Delay the audio to line up with its gain.
        val stride = latencyFrames + 1
        for (ch in 0 until channels) delay[ch * stride + delayPos] = input[ch]
        val readPos = (delayPos + 1) % stride
        for (ch in 0 until channels) output[ch] = delay[ch * stride + readPos] * gain
        delayPos = readPos
    }

    companion object {
        private const val PHASES = 8
        private const val HALF = 8
        private const val TAPS = HALF * 2
        private const val RESUM_INTERVAL = 1 shl 16

        /**
         * Windowed-sinc kernels for the interpolated points k/8 of the way past the sample HALF
         * frames back. Phase 0 is the sample itself, exactly.
         */
        private val PHASE_KERNELS: Array<DoubleArray> =
            Array(PHASES) { phase ->
                DoubleArray(TAPS) { j ->
                    // history is read oldest-first: index j holds the sample TAPS-1-j frames back,
                    // so the point HALF frames back plus phase/8 is (HALF-1-j) + phase/8 from it.
                    val x = (HALF - 1 - j) + phase / PHASES.toDouble()
                    sincWindowed(x)
                }
            }

        /** Kaiser β; with 16 taps per phase this keeps the estimate within about 0.015 dB up to 18 kHz. */
        private const val KAISER_BETA = 5.0

        private fun sincWindowed(x: Double): Double {
            if (abs(x) >= HALF) return 0.0
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val r = x / HALF
            return sinc * besselI0(KAISER_BETA * sqrt(1.0 - r * r)) / besselI0(KAISER_BETA)
        }

        /** Modified Bessel function of the first kind, order 0 (series; converges fast for β ≤ 10). */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= (x / (2.0 * k)) * (x / (2.0 * k))
                sum += term
                k++
            }
            return sum
        }
    }
}
