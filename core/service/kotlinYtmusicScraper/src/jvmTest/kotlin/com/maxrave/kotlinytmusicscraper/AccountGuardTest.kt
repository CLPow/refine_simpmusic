package com.maxrave.kotlinytmusicscraper

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountGuardTest {
    private var now = 1_000_000L
    private val guard = AccountGuard { now }

    @Test
    fun cooldownIsCappedAtTwoMinutes() =
        runBlocking {
            repeat(10) { guard.onResponse(429) }
            assertEquals(120_000L, guard.cooldownRemainingMs)
        }

    @Test
    fun streakResetsAfterQuietPeriod() =
        runBlocking {
            repeat(3) { guard.onResponse(429) }
            now += 11 * 60_000L
            guard.onResponse(429)
            assertEquals(30_000L, guard.cooldownRemainingMs)
        }

    @Test
    fun boundedWaitThrowsInsteadOfBlocking() =
        runBlocking {
            guard.onResponse(429)
            val error = assertFailsWith<AccountCoolingDownException> { guard.awaitTurn(maxWaitMs = 5_000L) }
            assertEquals(30_000L, error.remainingMs)
        }

    @Test
    fun successClearsCooldown() =
        runBlocking {
            guard.onResponse(429)
            assertTrue(guard.isCoolingDown)
            guard.onResponse(200)
            assertFalse(guard.isCoolingDown)
            assertEquals(0L, guard.cooldownUntil.value)
        }
}
