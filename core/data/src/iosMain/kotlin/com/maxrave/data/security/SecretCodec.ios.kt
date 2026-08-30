package com.maxrave.data.security

/** Pass-through: iOS app containers are private to the app, as on Android. */
internal actual object SecretCodec {
    actual fun encode(plain: String): String = plain

    actual fun decode(stored: String): String = stored
}
