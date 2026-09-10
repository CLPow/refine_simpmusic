package com.maxrave.data.security

/** Default [SecretCodec] purpose: the Google/YouTube session cookie. */
internal const val SECRET_PURPOSE_SESSION: String = "session"

/** Protects a stored credential at rest, where the platform offers somewhere to anchor that. */
internal expect object SecretCodec {
    /**
     * @param purpose names the credential so desktop keyrings keep separate slots (YouTube cookie
     *   vs Spotify `sp_dc` vs tokens). Without it every secret overwrote the same Keychain item.
     */
    fun encode(
        plain: String,
        purpose: String = SECRET_PURPOSE_SESSION,
    ): String

    fun decode(
        stored: String,
        purpose: String = SECRET_PURPOSE_SESSION,
    ): String

    /** True when the platform can protect credentials at rest. */
    fun isProtected(): Boolean
}
