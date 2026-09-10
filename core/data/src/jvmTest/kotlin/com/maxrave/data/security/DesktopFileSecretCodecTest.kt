package com.maxrave.data.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip and fail-closed behaviour for the file-key path. Windows DPAPI cannot run in a
 * portable unit test (it needs a real user profile), so the Linux/macOS AES-GCM path is the one
 * that is exercised here — and it is the one that previously had no coverage at all.
 */
class DesktopFileSecretCodecTest {
    private val sample = "SIDCC=very-secret-session-value; other=1"

    @Test
    fun encodeThenDecodeRoundTrips() {
        val encoded = DesktopFileSecretCodec.encode(sample)
        assertTrue(encoded.startsWith("aead:") || encoded.startsWith("kc:"), "encoded=$encoded")
        assertEquals(sample, DesktopFileSecretCodec.decode(encoded))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", DesktopFileSecretCodec.encode(""))
        assertEquals("", DesktopFileSecretCodec.decode(""))
    }

    @Test
    fun plaintextFromOlderInstallIsPassThroughOnDecode() {
        // An install that predates encryption still has the cookie in the clear. decode() must
        // accept it so the next write re-protects rather than invalidating the session.
        assertEquals(sample, DesktopFileSecretCodec.decode(sample))
    }

    @Test
    fun ciphertextIsNotThePlaintext() {
        val encoded = DesktopFileSecretCodec.encode(sample)
        assertTrue(!encoded.contains(sample), "ciphertext must not contain the plaintext")
    }
}
