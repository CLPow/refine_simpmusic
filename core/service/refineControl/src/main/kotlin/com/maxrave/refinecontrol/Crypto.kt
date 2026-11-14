package com.maxrave.refinecontrol

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of Refine Control, all from the JDK.
 *
 * Pairing: an ECDH P-256 exchange gives both devices one secret, and both show six digits derived
 * from it. Someone in the middle would have to run a separate exchange with each device, which
 * gives two different secrets and so two different codes; the caller commits to its key first
 * ([commitment]) so the codes cannot be searched for afterwards. The user comparing the codes is
 * what proves there is nobody in between.
 *
 * Afterwards: each connection derives fresh keys from the stored secret and both sides' nonces,
 * and every line is AES-256-GCM ([FrameCipher]).
 */
internal object Crypto {
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    fun newKeyPair(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()

    fun decodePublicKey(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun ecdh(
        own: KeyPair,
        peer: PublicKey,
    ): ByteArray =
        KeyAgreement
            .getInstance("ECDH")
            .apply {
                init(own.private)
                doPhase(peer, true)
            }.generateSecret()

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    fun commitment(
        publicKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray = sha256(publicKey, nonce)

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** HKDF-SHA256 (RFC 5869), for up to 32 bytes. */
    fun hkdf(
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        size: Int = 32,
    ): ByteArray {
        require(size in 1..32)
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, secret)
        return hmac(prk, info + byteArrayOf(1)).copyOf(size)
    }

    /** Everything both sides saw during pairing; a change to any of it changes the code and key. */
    fun transcript(
        callerKey: ByteArray,
        callerNonce: ByteArray,
        answererKey: ByteArray,
        answererNonce: ByteArray,
    ): ByteArray = sha256(callerKey, callerNonce, answererKey, answererNonce)

    /** The secret two paired devices keep. */
    fun pairingKey(
        shared: ByteArray,
        transcript: ByteArray,
    ): ByteArray = hkdf(shared, transcript, "refine-control/pairing-key".toByteArray())

    /** The six digits both screens show. */
    fun pairingCode(
        shared: ByteArray,
        transcript: ByteArray,
    ): String {
        val bytes = hkdf(shared, transcript, "refine-control/pairing-code".toByteArray(), 8)
        val number = ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
        return (number % 1_000_000L).toString().padStart(6, '0')
    }

    /** Keys of one connection: one per direction, so the two counters never meet. */
    fun frameCipher(
        pairingKey: ByteArray,
        callerNonce: ByteArray,
        answererNonce: ByteArray,
        isCaller: Boolean,
    ): FrameCipher {
        val salt = callerNonce + answererNonce
        val callerToAnswerer = hkdf(pairingKey, salt, "refine-control/caller".toByteArray())
        val answererToCaller = hkdf(pairingKey, salt, "refine-control/answerer".toByteArray())
        return if (isCaller) FrameCipher(callerToAnswerer, answererToCaller) else FrameCipher(answererToCaller, callerToAnswerer)
    }
}

/**
 * Encrypts and decrypts the lines of one connection.
 *
 * The GCM nonce is a counter that is not sent: each side counts the frames it has sent and
 * received. A frame that is replayed, dropped, reordered or changed therefore fails to open, and
 * the connection is closed.
 */
internal class FrameCipher(
    sendKey: ByteArray,
    receiveKey: ByteArray,
) {
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, "AES")
    private var sent = 0L
    private var received = 0L

    private fun nonce(counter: Long): GCMParameterSpec = GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(counter).array())

    @Synchronized
    fun seal(plain: ByteArray): ByteArray =
        Cipher
            .getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, sendKey, nonce(sent++)) }
            .doFinal(plain)

    /** Throws when the frame is not the next one from the holder of the key. */
    @Synchronized
    fun open(sealed: ByteArray): ByteArray =
        Cipher
            .getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, receiveKey, nonce(received)) }
            .doFinal(sealed)
            .also { received++ }
}
