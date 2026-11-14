package com.maxrave.data.security

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
