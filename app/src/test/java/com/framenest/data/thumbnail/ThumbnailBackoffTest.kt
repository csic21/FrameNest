package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailBackoffTest {

    @Test
    fun delays_increaseThenCap() {
        assertEquals(30_000L, ThumbnailBackoff.delayAfterAttempt(1))
        assertEquals(60_000L, ThumbnailBackoff.delayAfterAttempt(2))
        assertEquals(ThumbnailBackoff.MAX_DELAY_MS, ThumbnailBackoff.delayAfterAttempt(10))
    }

    @Test
    fun afterFailure_notEligibleUntilBackoffElapses() {
        val now = 1_000_000L
        val state = ThumbnailBackoff.afterFailure(ThumbnailBackoffState(), now)
        assertEquals(1, state.attempts)
        assertFalse(state.permanentlyFailed)
        assertFalse(state.isEligible(now))
        assertTrue(state.isEligible(now + ThumbnailBackoff.BASE_DELAY_MS))
    }

    @Test
    fun afterMaxAttempts_permanentlyFailed_noInfiniteRetry() {
        var state = ThumbnailBackoffState()
        val now = 0L
        repeat(ThumbnailBackoff.MAX_ATTEMPTS) {
            state = ThumbnailBackoff.afterFailure(state, now)
        }
        assertTrue(state.permanentlyFailed)
        assertEquals(ThumbnailBackoff.MAX_ATTEMPTS, state.attempts)
        assertFalse(state.isEligible(now + 365L * 24 * 60 * 60 * 1000))
    }

    @Test
    fun afterSuccess_resets() {
        val failed = ThumbnailBackoff.afterFailure(ThumbnailBackoffState(), 0L)
        val reset = ThumbnailBackoff.afterSuccess()
        assertEquals(0, reset.attempts)
        assertFalse(reset.permanentlyFailed)
        assertTrue(reset.isEligible(0L))
        assertTrue(failed.attempts > reset.attempts)
    }

    @Test
    fun neverExceedsMaxAttempts() {
        var state = ThumbnailBackoffState()
        repeat(20) {
            state = ThumbnailBackoff.afterFailure(state, it * 1_000L)
        }
        assertTrue(state.permanentlyFailed)
        assertEquals(ThumbnailBackoff.MAX_ATTEMPTS, state.attempts)
    }
}
