package com.maxrave.data.security

/**
 * iOS sandbox + Data Protection. Kept as a pass-through so the expect API stays symmetric;
 * if/when iOS ships, switch this to Keychain for the cookie specifically.
 */
internal actual object SecretCodec {
    actual fun encode(
        plain: String,
        purpose: String,
    ): String = plain

    actual fun decode(
        stored: String,
        purpose: String,
    ): String = stored

    actual fun isProtected(): Boolean = true
}
