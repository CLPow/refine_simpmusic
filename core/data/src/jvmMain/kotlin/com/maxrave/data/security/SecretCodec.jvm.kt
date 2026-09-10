package com.maxrave.data.security

import com.maxrave.logger.Logger
import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

private const val TAG = "SecretCodec"

/**
 * Marks a value this codec produced. Anything without it is plain text from an older install and
 * is returned as-is, then re-protected the next time it is written.
 */
private const val DPAPI_PREFIX = "dpapi:"

private val isWindows: Boolean
    get() = System.getProperty("os.name", "").contains("Windows", ignoreCase = true)

/**
 * Windows DPAPI, user-scoped.
 *
 * `CryptProtectData` ties the ciphertext to the logged-in Windows account, so the file is useless
 * if copied to another machine or read by another user.
 *
 * Encrypt failure returns "" so the caller can skip the write and keep a previously protected
 * session rather than silently signing the user out.
 */
internal actual object SecretCodec {
    actual fun encode(
        plain: String,
        purpose: String,
    ): String {
        if (plain.isEmpty()) return plain
        if (!isWindows) return DesktopFileSecretCodec.encode(plain, purpose)
        return try {
            val protectedBytes = Crypt32Util.cryptProtectData(plain.toByteArray(Charsets.UTF_8))
            DPAPI_PREFIX + Base64.getEncoder().encodeToString(protectedBytes)
        } catch (e: Throwable) {
            Logger.e(TAG, "DPAPI encrypt failed ($purpose); not storing unprotected: ${e.message}")
            ""
        }
    }

    actual fun decode(
        stored: String,
        purpose: String,
    ): String {
        if (stored.isEmpty()) return stored
        if (!isWindows) return DesktopFileSecretCodec.decode(stored, purpose)
        if (!stored.startsWith(DPAPI_PREFIX)) return stored
        val payload = stored.removePrefix(DPAPI_PREFIX)
        return try {
            val raw = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(payload))
            String(raw, Charsets.UTF_8)
        } catch (e: Throwable) {
            Logger.w(TAG, "DPAPI decrypt failed, treating as signed out: ${e.message}")
            ""
        }
    }

    actual fun isProtected(): Boolean = isWindows || DesktopFileSecretCodec.isAvailable()
}
