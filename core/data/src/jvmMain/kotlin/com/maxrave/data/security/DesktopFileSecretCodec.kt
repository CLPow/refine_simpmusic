package com.maxrave.data.security

import com.maxrave.logger.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "DesktopFileSecretCodec"
private const val PREFIX = "aead:"
private const val KC_PREFIX = "kc:"
private const val GCM_TAG_BITS = 128
private const val IV_BYTES = 12
private const val KEY_FILE_NAME = ".secret-key"
private const val KEYCHAIN_SERVICE = "org.simpmusic.secret"

/**
 * Non-Windows desktop protection.
 *
 * - macOS Keychain via the `security` CLI. Each [purpose] is its own generic-password account
 *   under [KEYCHAIN_SERVICE], so the YouTube cookie and Spotify tokens never overwrite each other.
 * - Linux / Keychain-fallback: AES-GCM with a 0600 key file under `~/.simpmusic/`. Better than
 *   cleartext DataStore contents, weaker than a system keyring — explicit, not claimed as DPAPI.
 *
 * Encrypt failure returns `""` so the caller can skip the write rather than store plaintext.
 */
internal object DesktopFileSecretCodec {
    private val isMac: Boolean
        get() = System.getProperty("os.name", "").contains("Mac", ignoreCase = true)

    private val isLinux: Boolean
        get() = System.getProperty("os.name", "").contains("Linux", ignoreCase = true)

    fun isAvailable(): Boolean = isMac || isLinux

    fun encode(
        plain: String,
        purpose: String,
    ): String {
        if (plain.isEmpty()) return plain
        if (isMac) {
            macKeychainStore(purpose, plain)?.let { return it }
            Logger.w(TAG, "Keychain store failed for $purpose; falling back to file key")
        }
        return try {
            encryptWithFileKey(plain)
        } catch (e: Throwable) {
            Logger.e(TAG, "File-key encrypt failed ($purpose); refusing to store unprotected: ${e.message}")
            ""
        }
    }

    fun decode(
        stored: String,
        purpose: String,
    ): String {
        if (stored.isEmpty()) return stored
        if (stored.startsWith(KC_PREFIX)) {
            return macKeychainLoad(stored.removePrefix(KC_PREFIX)) ?: ""
        }
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            decryptWithFileKey(stored.removePrefix(PREFIX))
        } catch (e: Throwable) {
            Logger.w(TAG, "File-key decrypt failed ($purpose), treating as signed out: ${e.message}")
            ""
        }
    }

    // ---- macOS Keychain ----

    private fun macKeychainStore(
        purpose: String,
        plain: String,
    ): String? {
        return try {
            val process =
                ProcessBuilder(
                    "security", "add-generic-password",
                    "-U",
                    "-a", purpose,
                    "-s", KEYCHAIN_SERVICE,
                    "-w", plain,
                ).redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val code = process.waitFor()
            if (code == 0) {
                KC_PREFIX + purpose
            } else {
                Logger.w(TAG, "security add-generic-password exit=$code $out")
                null
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "Keychain store unavailable: ${e.message}")
            null
        }
    }

    private fun macKeychainLoad(purpose: String): String? {
        return try {
            val process =
                ProcessBuilder(
                    "security", "find-generic-password",
                    "-a", purpose,
                    "-s", KEYCHAIN_SERVICE,
                    "-w",
                ).redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val code = process.waitFor()
            if (code == 0) out else null
        } catch (e: Throwable) {
            Logger.w(TAG, "Keychain load unavailable: ${e.message}")
            null
        }
    }

    // ---- File key (Linux / Keychain fallback) ----

    private fun keyFile(): File {
        val home = System.getProperty("user.home") ?: "."
        val dir = File(home, ".simpmusic")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("could not create $dir")
        }
        return File(dir, KEY_FILE_NAME)
    }

    private fun loadOrCreateKeyMaterial(): ByteArray {
        val file = keyFile()
        if (file.isFile && file.length() >= 32) {
            return file.readBytes().copyOf(32)
        }
        val material = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeBytes(material)
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX FS.
        } catch (e: Throwable) {
            Logger.w(TAG, "Could not tighten key file permissions: ${e.message}")
        }
        return material
    }

    private fun deriveKey(keyMaterial: ByteArray): SecretKey = SecretKeySpec(keyMaterial.copyOf(32), "AES")

    private fun encryptWithFileKey(plain: String): String {
        val keyMaterial = loadOrCreateKeyMaterial()
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(keyMaterial)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    private fun decryptWithFileKey(payload: String): String {
        val packed = Base64.getDecoder().decode(payload)
        require(packed.size > IV_BYTES) { "ciphertext too short" }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val key = deriveKey(loadOrCreateKeyMaterial())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
