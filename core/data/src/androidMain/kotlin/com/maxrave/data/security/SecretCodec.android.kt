package com.maxrave.data.security

/**
 * Pass-through. Android already keeps this file inside app-private storage, behind the app
 * sandbox and file-based encryption, so another app cannot read it without root.
 */
internal actual object SecretCodec {
    actual fun encode(plain: String): String = plain

    actual fun decode(stored: String): String = stored

    actual fun isProtected(): Boolean = true
}
