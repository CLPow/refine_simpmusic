package com.maxrave.kotlinytmusicscraper

import com.maxrave.logger.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "AccountGuard"

/** First pause after a refusal; doubles per consecutive refusal up to [MAX_COOLDOWN_MS]. */
private const val BASE_COOLDOWN_MS = 30_000L
private const val MAX_COOLDOWN_MS = 15 * 60_000L

/** Beyond this the backoff stops doubling, so the shift below cannot overflow. */
private const val MAX_BACKOFF_DOUBLINGS = 5

/**
 * Consecutive 403s before the guard treats them as an account-level refusal rather than a run of
 * unavailable items. High enough that ordinary region-locked or expired-URL responses never trip it.
 */
private const val FORBIDDEN_STREAK_BEFORE_BACKOFF = 8

/**
 * Stops traffic that carries the user's account while the service is refusing it.
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
    private var cooldownUntil = 0L
    private var consecutiveRefusals = 0
    private var consecutiveForbidden = 0

    /** Milliseconds until account traffic resumes; 0 when nothing is being held back. */
    val cooldownRemainingMs: Long
        get() = (cooldownUntil - now()).coerceAtLeast(0L)

    /** True while the service is refusing this account and the guard is standing down. */
    val isCoolingDown: Boolean
        get() = cooldownRemainingMs > 0L

    /**
     * Suspends while the service is refusing this account, and not otherwise.
     *
     * Requests are not spaced out. YouTube Music fires a page's calls together (player, next,
     * lyrics, related, the listening pings), and the 350 ms gap this used to impose queued every
     * one of them behind the last: opening a song, searching and paging Home all waited in line.
     *
     * Loops rather than sleeping once, because a refusal arriving while this call is waiting must
     * extend the wait.
     */
    suspend fun awaitTurn() {
        while (true) {
            val waitMs = mutex.withLock { (cooldownUntil - now()).coerceAtLeast(0L) }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    /**
     * Records how the service answered.
     *
     * Only 429 backs off. It is the one unambiguous "slow down", and responding to it is the whole
     * point of the guard.
     *
     * 403 deliberately does not, though an earlier version treated the two alike. On InnerTube a
     * 403 usually means *this item* is unavailable -- region-locked, age-gated, an expired stream
     * URL -- not that the account is being refused. Pausing every authenticated request for
     * thirty seconds because one track was unavailable is disproportionate, and worse, it starves
     * the home feed: its own load times out after thirty seconds, so a single routine 403 could
     * leave the user staring at a home page with no recommendations on it.
     */
    suspend fun onResponse(statusCode: Int) {
        mutex.withLock {
            when {
                statusCode == 429 -> {
                    consecutiveRefusals++
                    val doublings = (consecutiveRefusals - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS)
                    val backoff = (BASE_COOLDOWN_MS shl doublings).coerceAtMost(MAX_COOLDOWN_MS)
                    cooldownUntil = now() + backoff
                    Logger.w(
                        TAG,
                        "Rate limited (x$consecutiveRefusals); pausing account traffic for ${backoff / 1000}s",
                    )
                }

                statusCode == 403 -> {
                    // One 403 means one unavailable item. An unbroken run of them means the account
                    // itself is being refused, and continuing to issue three requests a second into
                    // that is how a soft block hardens -- so a streak still backs off, just far
                    // later than a 429 does.
                    consecutiveForbidden++
                    if (consecutiveForbidden >= FORBIDDEN_STREAK_BEFORE_BACKOFF) {
                        cooldownUntil = now() + BASE_COOLDOWN_MS
                        Logger.w(
                            TAG,
                            "$consecutiveForbidden consecutive 403s; pausing account traffic for " +
                                "${BASE_COOLDOWN_MS / 1000}s in case the account is being refused",
                        )
                    } else {
                        Logger.d(TAG, "403 on an account request ($consecutiveForbidden in a row)")
                    }
                }

                statusCode in 200..299 -> {
                    consecutiveForbidden = 0
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
