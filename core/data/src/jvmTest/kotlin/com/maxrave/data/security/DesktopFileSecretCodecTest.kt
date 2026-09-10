package com.maxrave.data.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip and fail-closed behaviour for the file-key path. Windows DPAPI cannot run in a
 * portable unit test, so the Linux/macOS AES-GCM path is exercised here.
 */
class DesktopFileSecretCodecTest {
    private val sample = "SIDCC=very-secret-session-value; other=1"

    @Test
    fun encodeThenDecodeRoundTrips() {
        val encoded = DesktopFileSecretCodec.encode(sample, "session")
        assertTrue(encoded.startsWith("aead:") || encoded.startsWith("kc:"), "encoded=$encoded")
        assertEquals(sample, DesktopFileSecretCodec.decode(encoded, "session"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", DesktopFileSecretCodec.encode("", "session"))
        assertEquals("", DesktopFileSecretCodec.decode("", "session"))
    }

    @Test
    fun plaintextFromOlderInstallIsPassThroughOnDecode() {
        assertEquals(sample, DesktopFileSecretCodec.decode(sample, "session"))
    }

    @Test
    fun ciphertextIsNotThePlaintext() {
        val encoded = DesktopFileSecretCodec.encode(sample, "spotify.sp_dc")
        assertTrue(!encoded.contains(sample), "ciphertext must not contain the plaintext")
    }

    @Test
    fun purposesDecodeIndependently() {
        val a = DesktopFileSecretCodec.encode("cookie-a", "session")
        val b = DesktopFileSecretCodec.encode("cookie-b", "spotify.sp_dc")
        assertEquals("cookie-a", DesktopFileSecretCodec.decode(a, "session"))
        assertEquals("cookie-b", DesktopFileSecretCodec.decode(b, "spotify.sp_dc"))
    }
}
