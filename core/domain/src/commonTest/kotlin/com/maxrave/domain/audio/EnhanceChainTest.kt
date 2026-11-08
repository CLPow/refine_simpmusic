package com.maxrave.domain.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnhanceChainTest {
    @Test
    fun offIsBypassWhateverElseIsSet() {
        val s = EnhanceSettings(enabled = false, style = EnhanceStyle.BASS, normalizeVolume = true, crossfeed = true, carSound = true)
        assertSame(EnhanceChain.BYPASS, enhanceChain(s, OutputRoute.CAR, 8f, 0.1f))
    }

    @Test
    fun normalizationIsTurnDownOnly() {
        assertEquals(-5.5, normalizationGainDb(5.5f))
        assertEquals(0.0, normalizationGainDb(-7f))
        assertEquals(0.0, normalizationGainDb(null))
        assertEquals(0.0, normalizationGainDb(40f)) // implausible values are ignored, not obeyed
    }

    @Test
    fun crossfeedOnlyOnHeadphonesAndCarOnlyInTheCar() {
        val s = EnhanceSettings(enabled = true, crossfeed = true, carSound = true)
        assertTrue(enhanceChain(s, OutputRoute.HEADPHONES, null).crossfeed)
        assertFalse(enhanceChain(s, OutputRoute.SPEAKER, null).crossfeed)
        assertTrue(enhanceChain(s, OutputRoute.CAR, null).leveller)
        assertFalse(enhanceChain(s, OutputRoute.HEADPHONES, null).leveller)
        assertFalse(enhanceChain(s.copy(carSound = false), OutputRoute.CAR, null).leveller)
    }

    @Test
    fun customCurveUsesAutoEqPreampOnClampedGains() {
        val s = EnhanceSettings(enabled = true, eqBandsDb = listOf(0f, 15f, 0f, -3f))
        val chain = enhanceChain(s, OutputRoute.SPEAKER, null)
        assertEquals(-12.0, chain.gainDb) // the +15 plays at +12, so it costs 12 dB, not 15
    }

    @Test
    fun theStageLayoutNeverChanges() {
        val variants =
            listOf(
                EnhanceSettings(enabled = true),
                EnhanceSettings(enabled = true, style = EnhanceStyle.VOCAL, eqBandsDb = listOf(3f)),
                EnhanceSettings(enabled = true, lowVolumeCompensation = true, carSound = true),
            )
        for (s in variants) {
            for (route in OutputRoute.entries) {
                for (volume in listOf(0f, 0.4f, 1f)) {
                    val filters = enhanceChain(s, route, 3f, volume).filters
                    assertEquals(ENHANCE_STAGE_COUNT, filters.size)
                }
            }
        }
    }

    @Test
    fun lowVolumeCompensationIsBudgetedAndNeverInTheCar() {
        val s = EnhanceSettings(enabled = true, lowVolumeCompensation = true, normalizeVolume = false)
        val quiet = enhanceChain(s, OutputRoute.HEADPHONES, null, 0.1f)
        assertEquals(6.0, quiet.lowVolumeDb)
        assertEquals(-2.0, quiet.gainDb)
        assertEquals(0.0, enhanceChain(s, OutputRoute.HEADPHONES, null, 0.7f).lowVolumeDb)
        assertEquals(0.0, enhanceChain(s.copy(carSound = true), OutputRoute.CAR, null, 0.1f).lowVolumeDb)
        // Bass style already lifts 4.5 dB: the total stays at 8.
        assertEquals(3.5, enhanceChain(s.copy(style = EnhanceStyle.BASS), OutputRoute.SPEAKER, null, 0.1f).lowVolumeDb, 1e-9)
    }

    @Test
    fun normalizeIsOnInsideEnhanceButTheMasterIsOff() {
        assertTrue(EnhanceSettings().normalizeVolume)
        assertFalse(EnhanceSettings().enabled)
    }

    @Test
    fun settingsSurviveStorageAndBadTextIsOff() {
        val s = EnhanceSettings(enabled = true, style = EnhanceStyle.VOCAL, eqBandsDb = listOf(1f, 2f))
        assertEquals(s, EnhanceSettings.decode(s.encode()))
        assertEquals(EnhanceSettings.OFF, EnhanceSettings.decode("{not json"))
        assertEquals(EnhanceSettings.OFF, EnhanceSettings.decode(null))
    }

    @Test
    fun autoEqFixedBandImport() {
        val text =
            """
            Preamp: -6.2 dB
            Filter 1: ON PK Fc 31 Hz Gain 5.8 dB Q 1.41
            Filter 2: ON PK Fc 62 Hz Gain -1.2 dB Q 1.41
            Filter 10: ON PK Fc 16000 Hz Gain -14.0 dB Q 1.41
            """.trimIndent()
        val bands = parseAutoEqFixedBand(text)!!
        assertEquals(5.8f, bands[0])
        assertEquals(-1.2f, bands[1])
        assertEquals(-12f, bands[9]) // clamped to the slider range
        assertNull(parseAutoEqFixedBand("Filter 1: ON PK Fc 87 Hz Gain 3 dB Q 1.41")) // not a band centre
        // A ParametricEQ profile is refused as a whole rather than squeezed onto the wrong bands.
        assertNull(parseAutoEqFixedBand("Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70\nFilter 2: ON PK Fc 62 Hz Gain -1.2 dB Q 1.41"))
        assertNull(parseAutoEqFixedBand("Filter 1: ON PK Fc 62 Hz Gain -1.2 dB Q 0.80"))
    }
}
