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
 * if copied to another machine or read by another user — and no key has to be invented, stored, or
 * kept next to the data it protects, which is the usual way home-grown encryption ends up
 * decorative.
 *
 * It does not defend against code already running as this user; nothing at rest can. What it
 * removes is the far more likely case: the session leaking through a backup, a synced folder, a
 * support bundle, or anything that reads the file without impersonating the user's session.
 *
 * Linux and macOS use [DesktopFileSecretCodec] (Keychain / Secret-Service / file key).
 */
internal actual object SecretCodec {
    actual fun encode(plain: String): String {
        if (plain.isEmpty()) return plain
        if (!isWindows) return DesktopFileSecretCodec.encode(plain)
        return try {
            val protectedBytes = Crypt32Util.cryptProtectData(plain.toByteArray(Charsets.UTF_8))
            DPAPI_PREFIX + Base64.getEncoder().encodeToString(protectedBytes)
        } catch (e: Throwable) {
            // Fail closed on the wire: do NOT fall back to plaintext silently. An empty encode
            // means the next write stores "" which decodes as signed-out. The log is the
            // diagnosis; the user re-signs in once DPAPI works again rather than leaking the
            // Google session into a synced settings file.
            Logger.e(TAG, "DPAPI encrypt failed; refusing to store session unprotected: ${e.message}")
            ""
        }
    }

    actual fun decode(stored: String): String {
        if (stored.isEmpty()) return stored
        if (!isWindows) return DesktopFileSecretCodec.decode(stored)
        if (!stored.startsWith(DPAPI_PREFIX)) return stored
        val payload = stored.removePrefix(DPAPI_PREFIX)
        return try {
            val raw = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(payload))
            String(raw, Charsets.UTF_8)
        } catch (e: Throwable) {
            // Wrong user, restored profile, or a corrupted value. Returning empty reads as
            // "signed out", which is recoverable by signing in again; returning the ciphertext
            // would be sent to YouTube as if it were a cookie.
            Logger.w(TAG, "DPAPI decrypt failed, treating as signed out: ${e.message}")
            ""
        }
    }

    actual fun isProtected(): Boolean = isWindows || DesktopFileSecretCodec.isAvailable()
}
