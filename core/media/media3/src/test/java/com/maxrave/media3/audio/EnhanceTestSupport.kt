package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.maxrave.domain.audio.EnhanceSettings
import com.maxrave.domain.audio.OutputRoute
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Drives an [EnhanceAudioProcessor] the way the audio sink does: configure, flush, then buffers. */
internal class Harness(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    settings: EnhanceSettings = EnhanceSettings.OFF,
    route: OutputRoute = OutputRoute.SPEAKER,
    var loudnessDb: Float? = null,
) {
    @Volatile var config = EnhanceConfig(settings = settings, route = route)
    val meter = EnhanceMeter()
    val processor = EnhanceAudioProcessor({ config }, { loudnessDb }, meter)

    fun start(encoding: Int = C.ENCODING_PCM_16BIT) {
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, encoding))
        processor.flush()
    }

    /** Runs interleaved PCM 16 through in [chunkFrames]-frame buffers and returns the output. */
    fun run16(
        input: ShortArray,
        chunkFrames: Int = 1024,
    ): ShortArray {
        val out = ShortArray(input.size)
        var pos = 0
        var outPos = 0
        while (pos < input.size) {
            val n = minOf(chunkFrames * channels, input.size - pos)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buf.putShort(input[pos + i])
            buf.flip()
            processor.queueInput(buf)
            val o = processor.output.order(ByteOrder.nativeOrder())
            while (o.remaining() >= 2) out[outPos++] = o.short
            pos += n
        }
        return out
    }

    fun runFloat(input: FloatArray): FloatArray {
        val buf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        input.forEach { buf.putFloat(it) }
        buf.flip()
        processor.queueInput(buf)
        val o = processor.output.order(ByteOrder.nativeOrder())
        return FloatArray(input.size) { o.float }
    }
}

/** Interleaved stereo PCM 16 from a per-frame generator returning full-scale doubles. */
internal fun stereo16(
    frames: Int,
    gen: (Int) -> Double,
): ShortArray =
    ShortArray(frames * 2) { i ->
        (gen(i / 2) * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
    }

/** True peak ([oversample]× windowed-sinc) of interleaved PCM 16, as a fraction of full scale. */
internal fun truePeak(
    pcm: ShortArray,
    channels: Int,
    fromFrame: Int = 0,
    oversample: Int = 16,
): Double {
    val half = 16
    var peak = 0.0
    val frames = pcm.size / channels
    for (ch in 0 until channels) {
        for (n in maxOf(fromFrame, half) until frames - half) {
            for (p in 0 until oversample) {
                val t = p / oversample.toDouble()
                var acc = 0.0
                for (k in -half + 1..half) {
                    val x = k - t
                    val s = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    val w = 0.5 * (1 + cos(PI * x / half))
                    acc += pcm[(n + k) * channels + ch] / 32768.0 * s * w
                }
                peak = max(peak, abs(acc))
            }
        }
    }
    return peak
}

/** Largest sample-to-sample step in [from, to) frames, over all channels, as a fraction of full scale. */
internal fun maxStep(
    pcm: ShortArray,
    from: Int,
    to: Int,
    channels: Int = 2,
): Double {
    var m = 0.0
    for (f in maxOf(1, from) until to) {
        for (ch in 0 until channels) m = maxOf(m, kotlin.math.abs(pcm[f * channels + ch] - pcm[(f - 1) * channels + ch]) / 32768.0)
    }
    return m
}

/** Longest run of frames in [from, to) whose every sample is within ±[threshold] of zero. */
internal fun longestNearSilentRun(
    pcm: ShortArray,
    from: Int,
    to: Int,
    channels: Int = 2,
    threshold: Int = 64,
): Int {
    var best = 0
    var run = 0
    for (f in from until to) {
        val quiet = (0 until channels).all { kotlin.math.abs(pcm[f * channels + it].toInt()) <= threshold }
        run = if (quiet) run + 1 else 0
        best = maxOf(best, run)
    }
    return best
}

internal fun rms(
    pcm: ShortArray,
    fromFrame: Int,
    channels: Int = 2,
): Double {
    var sum = 0.0
    var n = 0
    for (i in fromFrame * channels until pcm.size) {
        val v = pcm[i] / 32768.0
        sum += v * v
        n++
    }
    return kotlin.math.sqrt(sum / n)
}

/** Minimal PCM 16 WAV reader/writer for the render harness. */
internal object Wav {
    data class Clip(
        val sampleRate: Int,
        val channels: Int,
        val pcm: ShortArray,
    )

    fun read(file: File): Clip {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var ch = 0
        var data: ShortArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") {
                ch = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
            } else if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                data = ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size + (size and 1)
        }
        return Clip(rate, ch, requireNotNull(data) { "no data chunk in $file" })
    }

    fun write(
        file: File,
        clip: Clip,
    ) {
        val dataBytes = clip.pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(clip.channels.toShort())
        bb.putInt(clip.sampleRate).putInt(clip.sampleRate * clip.channels * 2).putShort((clip.channels * 2).toShort()).putShort(16)
        bb.put("data".toByteArray()).putInt(dataBytes)
        clip.pcm.forEach { bb.putShort(it) }
        file.writeBytes(bb.array())
    }
}
