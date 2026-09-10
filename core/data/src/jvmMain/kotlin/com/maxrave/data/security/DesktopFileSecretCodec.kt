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
private const val GCM_TAG_BITS = 128
private const val SALT_BYTES = 16
private const val IV_BYTES = 12
private const val KEY_FILE_NAME = ".secret-key"

/**
 * Non-Windows desktop protection.
 *
 * Prefer the platform store when it is usable:
 * - macOS Keychain via the `security` CLI (generic password, service `org.simpmusic.session`).
 * - Linux Secret Service is not wired here (needs libsecret + a running agent); fall through to
 *   an AES-GCM key file under the app data directory with POSIX 600.
 *
 * The file key is better than cleartext DataStore contents (backup/sync of the settings file no
 * longer exposes the cookie) and worse than a system keyring (anything that can read the key file
 * as this user can decrypt). That trade-off is explicit rather than claimed as DPAPI-equivalent.
 */
internal object DesktopFileSecretCodec {
    private val isMac: Boolean
        get() = System.getProperty("os.name", "").contains("Mac", ignoreCase = true)

    private val isLinux: Boolean
        get() = System.getProperty("os.name", "").contains("Linux", ignoreCase = true)

    fun isAvailable(): Boolean = isMac || isLinux

    fun encode(plain: String): String {
        if (plain.isEmpty()) return plain
        if (isMac) {
            macKeychainStore(plain)?.let { return it }
            Logger.w(TAG, "Keychain store failed; falling back to file key")
        }
        return try {
            encryptWithFileKey(plain)
        } catch (e: Throwable) {
            Logger.e(TAG, "File-key encrypt failed; refusing to store unprotected: ${e.message}")
            ""
        }
    }

    fun decode(stored: String): String {
        if (stored.isEmpty()) return stored
        if (stored.startsWith("kc:")) {
            return macKeychainLoad(stored.removePrefix("kc:")) ?: ""
        }
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            decryptWithFileKey(stored.removePrefix(PREFIX))
        } catch (e: Throwable) {
            Logger.w(TAG, "File-key decrypt failed, treating as signed out: ${e.message}")
            ""
        }
    }

    // ---- macOS Keychain ----

    private fun keychainAccount(): String = System.getProperty("user.name", "user")

    private fun macKeychainStore(plain: String): String? {
        return try {
            val process =
                ProcessBuilder(
                    "security", "add-generic-password",
                    "-U", // update if exists
                    "-a", keychainAccount(),
                    "-s", "org.simpmusic.session",
                    "-w", plain,
                ).redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val code = process.waitFor()
            if (code == 0) "kc:${keychainAccount()}" else {
                Logger.w(TAG, "security add-generic-password exit=$code $out")
                null
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "Keychain store unavailable: ${e.message}")
            null
        }
    }

    private fun macKeychainLoad(account: String): String? {
        return try {
            val process =
                ProcessBuilder(
                    "security", "find-generic-password",
                    "-a", account,
                    "-s", "org.simpmusic.session",
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
            // Non-POSIX FS (should not happen on Linux/macOS) — leave default ACLs.
        } catch (e: Throwable) {
            Logger.w(TAG, "Could not tighten key file permissions: ${e.message}")
        }
        return material
    }

    private fun deriveKey(keyMaterial: ByteArray): SecretKey = SecretKeySpec(keyMaterial.copyOf(32), "AES")

    private fun encryptWithFileKey(plain: String): String {
        val keyMaterial = loadOrCreateKeyMaterial()
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(keyMaterial)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = salt + iv + ciphertext
        return PREFIX + Base64.getEncoder().encodeToString(packed)
    }

    private fun decryptWithFileKey(payload: String): String {
        val packed = Base64.getDecoder().decode(payload)
        require(packed.size > SALT_BYTES + IV_BYTES) { "ciphertext too short" }
        val salt = packed.copyOfRange(0, SALT_BYTES)
        val iv = packed.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val ciphertext = packed.copyOfRange(SALT_BYTES + IV_BYTES, packed.size)
        val keyMaterial = loadOrCreateKeyMaterial()
        val key = deriveKey(keyMaterial)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
