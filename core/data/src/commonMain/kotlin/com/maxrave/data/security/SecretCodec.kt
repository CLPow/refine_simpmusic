package com.maxrave.data.security

internal const val SECRET_PURPOSE_SESSION: String = "session"

internal expect object SecretCodec {
    fun encode(
        plain: String,
        purpose: String = SECRET_PURPOSE_SESSION,
    ): String

    fun decode(
        stored: String,
        purpose: String = SECRET_PURPOSE_SESSION,
    ): String

    fun isProtected(): Boolean
}
