package com.maxrave.media3.audio

import com.maxrave.domain.audio.EnhanceSettings
import com.maxrave.domain.audio.EnhanceStyle
import com.maxrave.domain.audio.OutputRoute
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Offline render harness for the release gate (`tools/enhance-eval/gate.ps1`): runs every PCM 16
 * WAV in `ENHANCE_RENDER_IN` (or `-Denhance.render.in`) through the phone's own processor in each
 * variant and writes the results to `ENHANCE_RENDER_OUT`. Skipped unless both are given.
 *
 * The styles, car and crossfeed are rendered without normalization, so the gate judges each stage
 * on its own; `natural_norm` uses each clip's loudness from `loudness.json` (written by
 * prepare.py), the way YouTube's `loudnessDb` reaches the player.
 */
class EnhanceRenderTest {
    @Test
    fun render() {
        val inDir = (System.getProperty("enhance.render.in") ?: System.getenv("ENHANCE_RENDER_IN"))?.let(::File)
        val outDir = (System.getProperty("enhance.render.out") ?: System.getenv("ENHANCE_RENDER_OUT"))?.let(::File)
        assumeTrue(inDir != null && outDir != null && inDir.isDirectory)
        outDir!!.mkdirs()
        val loudness = readLoudness(File(inDir, "loudness.json"))
        val plain = EnhanceSettings(enabled = true, normalizeVolume = false)
        val variants =
            mapOf(
                "off" to (EnhanceSettings.OFF to OutputRoute.SPEAKER),
                "natural" to (plain to OutputRoute.SPEAKER),
                "natural_norm" to (EnhanceSettings(enabled = true, normalizeVolume = true) to OutputRoute.SPEAKER),
                "bass" to (plain.copy(style = EnhanceStyle.BASS) to OutputRoute.SPEAKER),
                "vocal" to (plain.copy(style = EnhanceStyle.VOCAL) to OutputRoute.SPEAKER),
                "car" to (plain.copy(carSound = true) to OutputRoute.CAR),
                "crossfeed" to (plain.copy(crossfeed = true) to OutputRoute.HEADPHONES),
            )
        inDir!!.listFiles { f -> f.extension.equals("wav", true) }!!.sorted().forEach { file ->
            val clip = Wav.read(file)
            variants.forEach { (name, v) ->
                val h =
                    Harness(
                        sampleRate = clip.sampleRate,
                        channels = clip.channels,
                        settings = v.first,
                        route = v.second,
                        loudnessDb = loudness[file.nameWithoutExtension],
                    )
                h.start()
                val out = h.run16(clip.pcm, chunkFrames = 2048)
                Wav.write(File(outDir, "${file.nameWithoutExtension}__$name.wav"), clip.copy(pcm = out))
            }
        }
    }

    /** `{"t1_ref": 7.3, …}` without pulling a JSON library into the tests. */
    private fun readLoudness(file: File): Map<String, Float> {
        if (!file.isFile) return emptyMap()
        return Regex("\"([^\"]+)\"\\s*:\\s*(-?[\\d.]+)").findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2].toFloat() }
    }
}
