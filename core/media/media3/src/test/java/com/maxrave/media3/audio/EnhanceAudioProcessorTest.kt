package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.maxrave.domain.audio.EnhanceFilter
import com.maxrave.domain.audio.EnhanceSettings
import com.maxrave.domain.audio.EnhanceStyle
import com.maxrave.domain.audio.OutputRoute
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class EnhanceAudioProcessorTest {
    private val rate = 48_000
    private val on = EnhanceSettings(enabled = true)

    private fun music(
        frames: Int,
        level: Double = 0.25,
    ): ShortArray {
        val rnd = Random(7)
        val noise = DoubleArray(frames) { rnd.nextDouble(-1.0, 1.0) }
        return stereo16(frames) { n ->
            level * (0.5 * sin(2 * PI * 110 * n / rate) + 0.3 * sin(2 * PI * 1_250 * n / rate) + 0.2 * noise[n])
        }
    }

    /** A steady two-tone signal with no silence: any click or gap shows up as a step or a dropout. */
    private fun tones(
        frames: Int,
        level: Double = 0.4,
    ): ShortArray = stereo16(frames) { n -> level * (0.6 * sin(2 * PI * 440 * n / rate) + 0.4 * sin(2 * PI * 3_000 * n / rate)) }

    /** The signal's own largest step, which a click-free output around a change must not exceed by much. */
    private fun ownStep(input: ShortArray) = maxStep(input, 0, input.size / 2)

    private fun assertNoClick(
        out: ShortArray,
        input: ShortArray,
        atFrame: Int,
        what: String,
    ) {
        val from = atFrame - rate / 100
        val to = atFrame + rate / 10
        val step = maxStep(out, from, to)
        assertTrue("$what: step $step vs the signal's own ${ownStep(input)}", step <= ownStep(input) * 1.5 + 2.0 / 32768)
        assertTrue("$what: dropout of ${longestNearSilentRun(out, from, to)} frames", longestNearSilentRun(out, from, to) <= 2)
    }

    // ---- Off = 2.3.8 ----

    @Test
    fun offIsBitExact() {
        val h = Harness()
        h.start()
        val input = music(rate * 2, level = 0.9)
        assertArrayEquals(input, h.run16(input))
    }

    @Test
    fun offMatchesTheTwoThreeEightProcessorSlot() {
        // What 2.3.8 ran in this slot: its equalizer processor, pinned flat.
        val legacy = Legacy238EqualizerProcessor { LegacyEqualizerCurve.FLAT }
        legacy.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        legacy.flush()
        val input = music(rate * 2, level = 0.95)
        val buf = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach { buf.putShort(it) }
        buf.flip()
        legacy.queueInput(buf)
        val lo = legacy.output.order(ByteOrder.nativeOrder())
        val legacyOut = ShortArray(input.size) { lo.short }

        val h = Harness()
        h.start()
        assertArrayEquals(legacyOut, h.run16(input, chunkFrames = rate * 2))
    }

    @Test
    fun offIsBitExactForFloat() {
        val h = Harness()
        h.start(C.ENCODING_PCM_FLOAT)
        val input = FloatArray(4_096) { (sin(it * 0.01) * 1.3).toFloat() } // includes overs: still untouched
        assertArrayEquals(input, h.runFloat(input), 0f)
    }

    @Test
    fun switchingOffReturnsToBitExact() {
        val h = Harness(settings = EnhanceSettings(enabled = true, style = EnhanceStyle.BASS))
        h.start()
        h.run16(music(rate))
        h.config = h.config.copy(settings = EnhanceSettings.OFF)
        h.run16(music(rate / 5)) // the fade back to the original happens in here
        val input = music(rate)
        assertArrayEquals(input, h.run16(input))
    }

    // ---- No clicks ----

    @Test
    fun switchingOnDoesNotClick() {
        val h = Harness()
        h.start()
        val input = tones(rate)
        val out = ShortArray(input.size)
        h.run16(input.copyOfRange(0, rate)).copyInto(out, 0) // off for the first half
        h.config = h.config.copy(settings = on)
        h.run16(input.copyOfRange(rate, input.size)).copyInto(out, rate)
        assertNoClick(out, input, rate / 2, "switch on")
    }

    @Test
    fun compareThenOffAndReEnablingMidFadeDoNotClick() {
        val h = Harness(settings = EnhanceSettings(enabled = true, style = EnhanceStyle.VOCAL))
        h.start()
        val input = tones(rate * 2)
        val parts = listOf(0 to rate / 2, rate / 2 to rate * 3 / 4, rate * 3 / 4 to rate * 3 / 4 + 480, rate * 3 / 4 + 480 to rate * 2)
        val out = ShortArray(input.size)
        parts.forEachIndexed { i, (a, b) ->
            when (i) {
                1 -> h.config = h.config.copy(compare = true)
                2 -> h.config = h.config.copy(settings = EnhanceSettings.OFF, compare = false)
                3 -> h.config = h.config.copy(settings = h.config.settings.copy(enabled = true)) // back on 10 ms into the fade
            }
            h.run16(input.copyOfRange(a * 2, b * 2), chunkFrames = 240).copyInto(out, a * 2)
        }
        assertNoClick(out, input, rate / 2, "compare")
        assertNoClick(out, input, rate * 3 / 4, "compare then off")
        assertNoClick(out, input, rate * 3 / 4 + 480, "re-enable mid fade")
    }

    @Test
    fun volumeStepAndEqSliderThroughZeroDoNotClick() {
        val h = Harness(settings = EnhanceSettings(enabled = true, lowVolumeCompensation = true, eqBandsDb = listOf(0f, 0f, 0f, 0f, 0f, -0.5f)))
        h.config = h.config.copy(volumeFraction = 0.66f)
        h.start()
        val input = tones(rate)
        val out = ShortArray(input.size)
        val q = rate / 4
        h.run16(input.copyOfRange(0, q * 2)).copyInto(out, 0)
        // Compensation comes in (its stages go from idle to active) …
        h.config = h.config.copy(volumeFraction = 0.64f)
        h.run16(input.copyOfRange(q * 2, q * 4)).copyInto(out, q * 2)
        // … and a slider passes through 0 dB.
        h.config = h.config.copy(settings = h.config.settings.copy(eqBandsDb = emptyList()))
        h.run16(input.copyOfRange(q * 4, q * 6)).copyInto(out, q * 4)
        h.config = h.config.copy(settings = h.config.settings.copy(eqBandsDb = listOf(0f, 0f, 0f, 0f, 0f, 0.5f)))
        h.run16(input.copyOfRange(q * 6, input.size)).copyInto(out, q * 6)
        listOf(q, q * 2, q * 3).forEach { assertNoClick(out, input, it, "change at frame $it") }
    }

    @Test
    fun reconfiguringTheSameInstanceKeepsWorking() {
        val h = Harness(settings = on)
        h.start()
        h.run16(music(rate / 2))
        // The sink reconfigures the same processor for a 44.1 kHz mono track.
        h.processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        h.processor.flush()
        val mono = ShortArray(44_100) { (sin(it * 0.05) * 8_000).toInt().toShort() }
        val buf = ByteBuffer.allocateDirect(mono.size * 2).order(ByteOrder.nativeOrder())
        mono.forEach { buf.putShort(it) }
        buf.flip()
        h.processor.queueInput(buf)
        val o = h.processor.output.order(ByteOrder.nativeOrder())
        assertEquals(mono.size * 2, o.remaining())
        val out = ShortArray(mono.size) { o.short }
        // Steady state: the same sine, delayed, unchanged in level.
        assertEquals(rms(mono, 22_050, 1), rms(out, 22_050, 1), 0.01 * rms(mono, 22_050, 1))
    }

    // ---- Peaks and levels ----

    @Test
    fun limiterHoldsTheTruePeakCeilingOnRandomPhaseHighTones() {
        val rnd = Random(11)
        repeat(4) {
            val freqs = DoubleArray(3) { rnd.nextDouble(5_000.0, 18_000.0) }
            val phases = DoubleArray(3) { rnd.nextDouble(0.0, 2 * PI) }
            val h = Harness(settings = EnhanceSettings(enabled = true, normalizeVolume = false))
            h.start()
            val input = stereo16(rate / 2) { n -> 0.42 * (0 until 3).sumOf { sin(2 * PI * freqs[it] * n / rate + phases[it]) } }
            val tp = truePeak(h.run16(input), 2, fromFrame = rate / 8)
            assertTrue("true peak ${20 * log10(tp)} dBTP over −1 dBTP (tones ${freqs.toList()})", 20 * log10(tp) <= -1.0 + 0.02)
        }
    }

    @Test
    fun naturalIsTransparentBelowTheCeiling() {
        val h = Harness(settings = on)
        h.start()
        val input = music(rate, level = 0.25)
        val out = h.run16(input)
        val latency = TruePeakLimiter(2, rate, -1.0).latencyFrames
        for (frame in rate / 10 until rate) {
            for (ch in 0..1) {
                val expected = input[(frame - latency) * 2 + ch]
                assertTrue("frame $frame", abs(out[frame * 2 + ch] - expected) <= 1)
            }
        }
    }

    @Test
    fun normalizationOnlyTurnsDown() {
        val loud = Harness(settings = on, loudnessDb = 6f)
        loud.start()
        val input = music(rate * 2, level = 0.2)
        assertEquals(-6.0, 20 * log10(rms(loud.run16(input), rate) / rms(input, rate)), 0.2)

        val quiet = Harness(settings = on, loudnessDb = -6f)
        quiet.start()
        assertEquals(0.0, 20 * log10(rms(quiet.run16(input), rate) / rms(input, rate)), 0.2)
    }

    @Test
    fun holdToCompareIsLevelMatchedToTheProcessedSound() {
        val input = music(rate * 2, level = 0.6)
        fun level(compare: Boolean): Double {
            val h = Harness(settings = on, loudnessDb = 5f)
            h.config = h.config.copy(compare = compare)
            h.start()
            return rms(h.run16(input), rate)
        }
        assertEquals(0.0, 20 * log10(level(true) / level(false)), 0.2)
    }

    @Test
    fun peakingStageHasItsGainAtTheCentre() {
        val c = biquadCoefficients(EnhanceFilter.Peaking(1_000.0, 1.41, 6.0), rate)
        val w = 2 * PI * 1_000 / rate
        val num = hypot(c[0] + c[1] * cos(-w) + c[2] * cos(-2 * w), c[1] * sin(-w) + c[2] * sin(-2 * w))
        val den = hypot(1 + c[3] * cos(-w) + c[4] * cos(-2 * w), c[3] * sin(-w) + c[4] * sin(-2 * w))
        assertEquals(6.0, 20 * log10(num / den), 0.01)
    }

    @Test
    fun levellerHoldsStillWhileWarmingUp() {
        val leveller = Leveller(rate)
        val frame = DoubleArray(2)
        // Nine seconds alternating loud and quiet: a leveller without warm-up would already move.
        for (n in 0 until rate * 9) {
            val level = if ((n / rate) % 2 == 0) 0.5 else 0.05
            frame[0] = level * sin(2 * PI * 1_000 * n / rate)
            frame[1] = frame[0]
            leveller.next(frame, 2)
        }
        assertEquals(0.0, leveller.gainDb, 0.0)
    }

    @Test
    fun carRouteRunsTheLevellerAndStaysWithinTheCeiling() {
        val h = Harness(settings = EnhanceSettings(enabled = true, carSound = true), route = OutputRoute.CAR)
        h.start()
        // Band-limited like a real stream (YouTube's Opus is lowpassed near 20 kHz), and loud
        // enough that the limiter works hard throughout.
        val loud = stereo16(rate * 2) { n -> 0.5 * sin(2 * PI * 110 * n / rate) + 0.3 * sin(2 * PI * 1_250 * n / rate) + 0.2 * sin(2 * PI * 7_000 * n / rate) }
        val out = h.run16(loud)
        val tp = 20 * log10(truePeak(out, 2, fromFrame = rate, oversample = 16))
        assertTrue("car true peak $tp dBTP", tp <= -1.0 + 0.05)
    }
}
