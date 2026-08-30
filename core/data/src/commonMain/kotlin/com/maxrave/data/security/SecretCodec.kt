package com.maxrave.data.security

/**
 * Protects a stored credential at rest, where the platform offers somewhere to anchor that.
 *
 * The YouTube cookie is a full Google session — not merely Music access — and it was written to
 * disk verbatim. On Android that is tolerable: app-private storage plus file-based encryption keep
 * other apps out. On desktop it is not, because `~/.simpmusic/settings.preferences_pb` is an
 * ordinary file under the user's own account, readable by anything else running as them: a backup
 * or sync client, an installer script, a game mod, a coding agent.
 *
 * [encode] is therefore a no-op on platforms whose sandbox already provides the guarantee, and
 * real encryption where it does not.
 *
 * Values are round-tripped through [decode], which must accept anything [encode] has ever
 * produced *and* plain text, so an existing install keeps working and is re-protected on the next
 * write rather than being invalidated.
 */
internal expect object SecretCodec {
    fun encode(plain: String): String

    fun decode(stored: String): String
}
