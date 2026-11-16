package com.maxrave.kotlinytmusicscraper

import com.maxrave.logger.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "AccountGuard"

private const val BASE_COOLDOWN_MS = 30_000L
private const val MAX_COOLDOWN_MS = 120_000L

private const val MAX_BACKOFF_DOUBLINGS = 2

private const val STREAK_RESET_MS = 10 * 60_000L

// High enough that ordinary region-locked or expired-URL responses never trip it.
private const val FORBIDDEN_STREAK_BEFORE_BACKOFF = 8

class AccountCoolingDownException(
    val remainingMs: Long,
) : Exception("Account cooling down for ${remainingMs / 1000}s")

@OptIn(ExperimentalTime::class)
class AccountGuard(
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var lastRefusalAt = 0L
    private var consecutiveRefusals = 0
    private var consecutiveForbidden = 0

    private val _cooldownUntil = MutableStateFlow(0L)
    val cooldownUntil: StateFlow<Long> = _cooldownUntil.asStateFlow()

    val cooldownRemainingMs: Long
        get() = (_cooldownUntil.value - clock()).coerceAtLeast(0L)

    val isCoolingDown: Boolean
        get() = cooldownRemainingMs > 0L

    suspend fun awaitTurn(maxWaitMs: Long = Long.MAX_VALUE) {
        while (true) {
            val waitMs = mutex.withLock { (_cooldownUntil.value - clock()).coerceAtLeast(0L) }
            if (waitMs <= 0L) return
            if (waitMs > maxWaitMs) throw AccountCoolingDownException(waitMs)
            delay(waitMs)
        }
    }

    suspend fun onResponse(statusCode: Int) {
        mutex.withLock {
            val now = clock()
            when {
                statusCode == 429 -> {
                    if (now - lastRefusalAt > STREAK_RESET_MS) consecutiveRefusals = 0
                    lastRefusalAt = now
                    consecutiveRefusals++
                    val doublings = (consecutiveRefusals - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS)
                    val backoff = (BASE_COOLDOWN_MS shl doublings).coerceAtMost(MAX_COOLDOWN_MS)
                    _cooldownUntil.value = now + backoff
                    Logger.w(
                        TAG,
                        "Rate limited (x$consecutiveRefusals); pausing account traffic for ${backoff / 1000}s",
                    )
                }

                statusCode == 403 -> {
                    consecutiveForbidden++
                    if (consecutiveForbidden >= FORBIDDEN_STREAK_BEFORE_BACKOFF) {
                        _cooldownUntil.value = now + BASE_COOLDOWN_MS
                        consecutiveForbidden = 0
                        Logger.w(
                            TAG,
                            "$FORBIDDEN_STREAK_BEFORE_BACKOFF consecutive 403s; pausing account traffic for " +
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
                    _cooldownUntil.value = 0L
                }
            }
        }
    }
}
