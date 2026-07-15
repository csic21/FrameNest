package com.framenest.feature.listen_translate

import com.framenest.data.listen_translate.ListenLanguagePair
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenWindowRetryPolicyTest {

    @Test
    fun failuresBackOffExponentially_andSuccessClearsState() {
        var now = 1_000L
        val policy = ListenWindowRetryPolicy(
            nowMs = { now },
            baseDelayMs = 100L,
            maxDelayMs = 1_000L,
        )
        val key = ListenWindowAttemptKey(0L, 3_000L, ListenLanguagePair("en", "zh"))

        assertTrue(policy.canAttempt(key))
        policy.recordFailure(key)
        assertFalse(policy.canAttempt(key))
        now += 100L
        assertTrue(policy.canAttempt(key))

        policy.recordFailure(key)
        now += 199L
        assertFalse(policy.canAttempt(key))
        now += 1L
        assertTrue(policy.canAttempt(key))

        policy.recordSuccess(key)
        assertTrue(policy.canAttempt(key))
    }

    @Test
    fun permanentFailuresStayBlockedUntilPolicyIsCleared() {
        val policy = ListenWindowRetryPolicy(nowMs = { Long.MAX_VALUE - 1L })
        val key = ListenWindowAttemptKey(0L, 3_000L, ListenLanguagePair("en", "zh"))

        policy.recordPermanentFailure(key)
        assertFalse(policy.canAttempt(key))
        policy.clear()
        assertTrue(policy.canAttempt(key))
        assertTrue(
            isNonRetryableListenFailure(
                IllegalStateException("decode", UnsupportedOperationException("codec")),
            ),
        )
    }
}
