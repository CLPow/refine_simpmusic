package com.maxrave.kotlinytmusicscraper

import com.maxrave.logger.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "AccountGuard"

private const val BASE_COOLDOWN_MS = 30_000L
private const val MAX_COOLDOWN_MS = 15 * 60_000L

private const val MAX_BACKOFF_DOUBLINGS = 5

// High enough that ordinary region-locked or expired-URL responses never trip it.
private const val FORBIDDEN_STREAK_BEFORE_BACKOFF = 8

@OptIn(ExperimentalTime::class)
class AccountGuard {
    private val mutex = Mutex()
    private var cooldownUntil = 0L
    private var consecutiveRefusals = 0
    private var consecutiveForbidden = 0

    val cooldownRemainingMs: Long
        get() = (cooldownUntil - now()).coerceAtLeast(0L)

    val isCoolingDown: Boolean
        get() = cooldownRemainingMs > 0L

    suspend fun awaitTurn() {
        while (true) {
            val waitMs = mutex.withLock { (cooldownUntil - now()).coerceAtLeast(0L) }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

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
