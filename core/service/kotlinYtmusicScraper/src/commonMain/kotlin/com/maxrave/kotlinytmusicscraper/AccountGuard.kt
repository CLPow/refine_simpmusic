package com.maxrave.kotlinytmusicscraper

import com.maxrave.logger.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "AccountGuard"

/**
 * Smallest gap between two requests that carry the signed-in account.
 *
 * Chosen to be invisible while a person browses — opening a screen fires a handful of calls and
 * still feels instant — while making it impossible for any feature to burst hundreds of
 * account-bearing requests at the service. A bulk sweep is what unusual traffic looks like, and
 * this fork has already degraded one account's recommendations that way.
 */
private const val MIN_INTERVAL_MS = 350L

/** First pause after a refusal; doubles per consecutive refusal up to [MAX_COOLDOWN_MS]. */
private const val BASE_COOLDOWN_MS = 30_000L
private const val MAX_COOLDOWN_MS = 15 * 60_000L

/** Beyond this the backoff stops doubling, so the shift below cannot overflow. */
private const val MAX_BACKOFF_DOUBLINGS = 5

/**
 * Paces and, when refused, stops traffic that carries the user's account.
 *
 * The app is an unofficial client, so the account is the thing with something to lose. Two failure
 * shapes matter, and neither is hypothetical here: a feature firing a burst of authenticated
 * requests (which visibly degraded recommendations once), and a feature retrying into a refusal
 * that the service has already issued, which turns a temporary block into a longer one.
 *
 * This guards both centrally, at the one place every request is built, rather than trusting each
 * caller to be careful. A caller that forgets is exactly the caller that needs it.
 *
 * Requests without the account attached — the signed-out searches the Spotify import uses, for
 * instance — are deliberately not paced. They cannot harm an account, and slowing them would make
 * the safe path the slow one.
 */
@OptIn(ExperimentalTime::class)
class AccountGuard {
    private val mutex = Mutex()
    private var nextAllowedAt = 0L
    private var cooldownUntil = 0L
    private var consecutiveRefusals = 0

    /** Milliseconds until account traffic resumes; 0 when nothing is being held back. */
    val cooldownRemainingMs: Long
        get() = (cooldownUntil - now()).coerceAtLeast(0L)

    /** True while the service is refusing this account and the guard is standing down. */
    val isCoolingDown: Boolean
        get() = cooldownRemainingMs > 0L

    /**
     * Suspends until this request may go out.
     *
     * Loops rather than sleeping once, because a refusal arriving while this call is waiting must
     * extend the wait — otherwise the request that triggered the cooldown is followed straight
     * away by the one queued behind it.
     */
    suspend fun awaitTurn() {
        while (true) {
            val waitMs =
                mutex.withLock {
                    val now = now()
                    val readyAt = maxOf(cooldownUntil, nextAllowedAt)
                    if (now >= readyAt) {
                        nextAllowedAt = now + MIN_INTERVAL_MS
                        0L
                    } else {
                        readyAt - now
                    }
                }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    /**
     * Records how the service answered.
     *
     * 429 is an explicit "slow down". 403 on an account-bearing request generally means the
     * session is being refused rather than the content being unavailable, and hammering through it
     * is how a soft block becomes a hard one. Both back off; anything successful clears the streak.
     */
    suspend fun onResponse(statusCode: Int) {
        mutex.withLock {
            when {
                statusCode == 429 || statusCode == 403 -> {
                    consecutiveRefusals++
                    val doublings = (consecutiveRefusals - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS)
                    val backoff = (BASE_COOLDOWN_MS shl doublings).coerceAtMost(MAX_COOLDOWN_MS)
                    cooldownUntil = now() + backoff
                    Logger.w(
                        TAG,
                        "Account traffic refused with $statusCode " +
                            "(x$consecutiveRefusals); pausing for ${backoff / 1000}s",
                    )
                }

                statusCode in 200..299 -> {
                    if (consecutiveRefusals > 0) {
                        Logger.i(TAG, "Account traffic accepted again; clearing backoff")
                    }
                    consecutiveRefusals = 0
                    cooldownUntil = 0L
                }
            }
        }
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}
